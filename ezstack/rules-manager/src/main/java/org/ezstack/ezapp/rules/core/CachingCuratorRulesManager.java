package org.ezstack.ezapp.rules.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.*;
import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.*;
import org.apache.curator.utils.ZKPaths;
import org.apache.logging.log4j.util.Strings;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.ezstack.ezapp.datastore.api.Rule;
import org.ezstack.ezapp.datastore.api.RuleAlreadyExistsException;
import org.ezstack.ezapp.datastore.api.RulesManager;
import org.ezstack.ezapp.rules.api.ConsistentRulesManager;
import org.ezstack.ezapp.rules.api.RulesPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.MoreObjects.firstNonNull;
import static org.ezstack.ezapp.datastore.api.Rule.RuleStatus;

public class CachingCuratorRulesManager extends AbstractService implements RulesManager {

    private final static int TIME_BETWEEN_REINDEXING_SECONDS = 10;

    private final static Logger LOG = LoggerFactory.getLogger(CachingCuratorRulesManager.class);

    private CuratorFramework _client;

    private final CuratorFactory _curatorFactory;
    private final RulesManager _consistentRulesManager;

    private final String _rulesPath;
    private final ObjectMapper _mapper;

    // outer table, inner table
    private volatile Supplier<ImmutableTable<String, String, Set<Rule>>> _activeRuleIndex;
    private TreeCache _ruleCache;

    // <rule table name, rule itself>
    private volatile ConcurrentHashMap<String, Rule> _rules;

    @Inject
    public CachingCuratorRulesManager(@RulesPath String rulesPath,
                                      CuratorFactory curatorFactory,
                                      @ConsistentRulesManager RulesManager consistentRulesManager) {

        _rulesPath = checkNotNull(rulesPath, "rulesPath");
        _curatorFactory = checkNotNull(curatorFactory, "curatorFactory");
        _consistentRulesManager = checkNotNull(consistentRulesManager, "consistentRulesManager");

        _mapper = new ObjectMapper();
        _rules = new ConcurrentHashMap<>();
        _activeRuleIndex = Suppliers.memoizeWithExpiration(this::getActiveRuleIndex, TIME_BETWEEN_REINDEXING_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    protected void doStart() {
        try {
            _client = _curatorFactory.getStartedCuratorFramework();

            _ruleCache = new TreeCache(_client, _rulesPath);
            _ruleCache.getListenable().addListener(this::updateTableForEvent);

            _client.start();
            _ruleCache.start();

        } catch (Exception e) {
            notifyFailed(e);
            throw new RuntimeException(e);
        }

        notifyStarted();
    }

    @Override
    protected void doStop() {
        try {
            _ruleCache.close();
            _client.close();
        } catch (Exception e) {
            notifyFailed(e);
            throw e;
        }

        notifyStopped();
    }

    private void handleAddOrUpdateEvent(TreeCacheEvent event) {
        ChildData childData = event.getData();
        int pathLength = ZKPaths.split(childData.getPath()).size();

        if (pathLength <= 1) {
            // This is the parent, it can be ignored
            return;
        }

        if (pathLength >= 3) {
            LOG.info("Ignoring acknowledgement node {}", childData.getPath(), event);
            return;
        }

        try {
            Rule rule = _mapper.readValue(childData.getData(), Rule.class);
            _rules.put(rule.getTable(), rule);
            LOG.info("Received update for rule with table {}. Current status is {}", rule.getTable(), rule.getStatus());
        } catch (IOException e) {
            LOG.error(e.toString());
            LOG.error("Rule cache is corrupted. Corrupt rules will be ignored");
        }
    }

    private ImmutableTable<String, String, Set<Rule>> getActiveRuleIndex() {
        LOG.info("Building rule index");
        Table<String, String, Set<Rule>> table = HashBasedTable.create();
        _rules.values()
                .stream()
                .filter(rule -> rule.getStatus() == Rule.RuleStatus.ACTIVE)
                .forEach(rule -> {
                    String innerKey = rule.getQuery().getJoin() != null ? rule.getQuery().getJoin().getTable() : Strings.EMPTY;
                    Set<Rule> applicableRules = firstNonNull(table.get(rule.getQuery().getTable(), innerKey), new HashSet<>());
                    applicableRules.add(rule);
                    table.put(rule.getQuery().getTable(), innerKey, applicableRules);
                });

        return table.cellSet()
                .stream()
                .collect(ImmutableTable.toImmutableTable(Table.Cell::getRowKey, Table.Cell::getColumnKey,
                        cell -> Collections.unmodifiableSet(firstNonNull(cell.getValue(), ImmutableSet.of()))));
    }

    private void updateTableForEvent(CuratorFramework client, TreeCacheEvent event) {
        switch (event.getType()) {
            //fall through
            case NODE_ADDED:
            case NODE_UPDATED:
                handleAddOrUpdateEvent(event);
                return;
            case INITIALIZED:
                return;
            default:
                LOG.error("Zookeeper cache is experiencing issues");
                LOG.error(event.toString());
        }
    }

    @Override
    public void createRule(Rule rule) throws RuleAlreadyExistsException {
        // check to make sure they are submitting a pending rule
        checkArgument(rule.getStatus() == Rule.RuleStatus.PENDING);
        _consistentRulesManager.createRule(rule);
    }

    @Override
    public void setRuleStatus(String ruleTable, RuleStatus status) {
        _consistentRulesManager.setRuleStatus(ruleTable, status);
    }

    @Override
    public Rule getRule(String ruleTable) {
        Rule rule;
        if ((rule = _rules.get(ruleTable)) != null) {
            return rule;
        }

        return _consistentRulesManager.getRule(ruleTable);
    }

    @Override
    public void removeRule(String ruleTable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Rule> getRules() {
        return ImmutableSet.copyOf(_rules.values());
    }

    @Override
    public Set<Rule> getRules(String outerTable, RuleStatus status) {
        if (status == RuleStatus.ACTIVE) {
            return _activeRuleIndex.get()
                    .row(outerTable)
                    .values()
                    .stream()
                    .flatMap(Set::stream)
                    .collect(ImmutableSet.toImmutableSet());
        }

        return _rules.values()
                .stream()
                .filter(rule -> rule.getQuery().getTable().equals(outerTable))
                .filter(rule -> rule.getStatus() == status)
                .collect(ImmutableSet.toImmutableSet());
    }

    @Override
    public Set<Rule> getRules(String outerTable, String innerTable, RuleStatus status) {
        if (status == RuleStatus.ACTIVE) {
            return firstNonNull(_activeRuleIndex.get().get(outerTable, innerTable), Collections.emptySet());
        }

        return _rules.values()
                .stream()
                .filter(rule -> rule.getQuery().getTable().equals(outerTable))
                .filter(rule -> rule.getQuery().getJoin() != null)
                .filter(rule -> rule.getQuery().getJoin().getTable().equals(innerTable))
                .filter(rule -> rule.getStatus() == status)
                .collect(ImmutableSet.toImmutableSet());
    }

    @Override
    public Set<Rule> getRules(RuleStatus status) {
        return _rules.values()
                .stream()
                .filter(rule -> rule.getStatus() == status)
                .collect(ImmutableSet.toImmutableSet());
    }

}
