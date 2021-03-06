
package org.ezstack.samza;

import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SamzaScheduler implements Scheduler {
    private static final Logger LOG = LoggerFactory.getLogger(SamzaScheduler.class);
    private final List<String> pendingInstance = new ArrayList<>();
    private final List<String> runningInstance = new ArrayList<>();
    private final AtomicInteger taskIDGenerator = new AtomicInteger();

    private MesosConfig mesosConfig;

    public SamzaScheduler(MesosConfig config) {
        mesosConfig = config;
    }

    public void registered(SchedulerDriver schedulerDriver, Protos.FrameworkID frameworkID, Protos.MasterInfo masterInfo) {
        LOG.info("Registered " + frameworkID);
    }

    public void reregistered(SchedulerDriver schedulerDriver, Protos.MasterInfo masterInfo) {
        LOG.info("Reregistered");
    }

    public void resourceOffers(SchedulerDriver schedulerDriver, List<Protos.Offer> offers) {
        for (Protos.Offer offer: offers) {
            List<Protos.TaskInfo> tasks = new ArrayList<>();
            List<Protos.OfferID> offerIds = new ArrayList<>();

            if (runningInstance.size() + pendingInstance.size() < mesosConfig.getExecutorTaskCount()) {
                // Generate Unique Task ID
                Protos.TaskID taskId = Protos.TaskID.newBuilder()
                        .setValue(Integer.toString(taskIDGenerator.incrementAndGet())).build();

                // Launch Task
                pendingInstance.add(taskId.getValue());

                String containerId = "samza-task-" + taskId.getValue();
                String url = offer.getUrl().getAddress().getIp() + ":" + offer.getUrl().getAddress().getPort();
                LOG.info("Offer id: {}, Url: {}", containerId, url);

                // TODO: Docker stuff Maybe

                // Create Mesos Task To Run
                Protos.TaskInfo task = Protos.TaskInfo.newBuilder()
                        .setName(containerId)
                        .setTaskId(taskId)
                        .setSlaveId(offer.getSlaveId())
                        .addResources(getResourceBuilder("cpus", mesosConfig.getExecutorMaxCpuCores()))
                        .addResources(getResourceBuilder("mem", mesosConfig.getExecutorMaxMemoryMb()))
                        .addResources(getResourceBuilder("disk", mesosConfig.getExecutorMaxDiskMb()))
                        .setCommand(getCommand())
                        .build();
                tasks.add(task);
            }
            offerIds.add(offer.getId());
            Protos.Filters filter = Protos.Filters.newBuilder().setRefuseSeconds(1).build();
            schedulerDriver.launchTasks(offerIds, tasks, filter);
        }
    }

    public void offerRescinded(SchedulerDriver schedulerDriver, Protos.OfferID offerID) {
        LOG.info("offer rescinded");
    }

    public void statusUpdate(SchedulerDriver schedulerDriver, Protos.TaskStatus taskStatus) {
        final String taskId = taskStatus.getTaskId().getValue();

        switch (taskStatus.getState()) {
            case TASK_RUNNING:
                pendingInstance.remove(taskId);
                runningInstance.add(taskId);
                break;
            case TASK_FAILED: // fall through
                LOG.error("Task Failed: {}", taskStatus.getMessage());
            case TASK_LOST:
            case TASK_KILLED:
            case TASK_FINISHED:
                pendingInstance.remove(taskId);
                runningInstance.remove(taskId);
                break;
        }
        LOG.info("Running Instance Count: {}, Pending Instance Count: {}", runningInstance.size(), pendingInstance.size());
    }

    public void frameworkMessage(SchedulerDriver schedulerDriver, Protos.ExecutorID executorID,
                                 Protos.SlaveID slaveID, byte[] bytes) {
        LOG.info("Framework (scheduler) message: {}", new String(bytes));
    }

    public void disconnected(SchedulerDriver schedulerDriver) {
        LOG.info("Framework has been disconnected.");
    }

    public void slaveLost(SchedulerDriver schedulerDriver, Protos.SlaveID slaveID) {
        LOG.error("Slave " + slaveID.getValue() + " has been lost.");
    }

    public void executorLost(SchedulerDriver schedulerDriver, Protos.ExecutorID executorID, Protos.SlaveID slaveID, int i) {
        LOG.error("Executor {} on Slave {} has been lost.", executorID.getValue(), slaveID.getValue());
    }

    public void error(SchedulerDriver schedulerDriver, String s) {
        LOG.error("Error Report: {}", s);
    }

    private Protos.Resource.Builder getResourceBuilder(String resource, double value) {
        return Protos.Resource.newBuilder()
                .setName(resource)
                .setType(Protos.Value.Type.SCALAR)
                .setScalar(Protos.Value.Scalar.newBuilder()
                        .setValue(value));
    }

    private Protos.CommandInfo getCommand() {
        String cmd = getCmd();
        LOG.info("exec command: {}", cmd);


        return Protos.CommandInfo.newBuilder()
                .addUris(Protos.CommandInfo.URI.newBuilder()
                        .setValue(mesosConfig.getPackagePath())
                        .setExtract(true)
                        .build())
                .setValue(cmd)
                .setEnvironment(getBuiltMesosEnvironment())
                .build();
    }

    private String getCmd() {
        return mesosConfig.getCommand();
    }

    private Protos.Environment getBuiltMesosEnvironment() {
        Protos.Environment.Builder envBuilder = Protos.Environment.newBuilder();
        String mem = String.valueOf(mesosConfig.getExecutorMaxMemoryMb());
        envBuilder.addVariables(Protos.Environment.Variable.newBuilder()
                .setName("JAVA_HEAP_OPTS")
                .setValue("-Xms" + mem + "M -Xmx" + mem + "M")
                .build());
        return envBuilder.build();
    }
}
