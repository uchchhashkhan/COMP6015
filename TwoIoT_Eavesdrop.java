package org.fog.test.custom;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerTimeSharedOverSubscription;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.*;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.power.PowerModelLinear;
import org.fog.utils.FogUtils;
import org.cloudbus.cloudsim.NetworkTopology;
import org.cloudbus.cloudsim.Pe;
import org.fog.utils.distribution.DeterministicDistribution;

import java.util.*;

public class TwoIoT_Eavesdrop {
    private static final String APP_ID = "iot_talk";
    private static final boolean WITH_ATTACKER = true; // flip to false to simulate no eavesdropping

    // tuple names
    private static final String SENSOR_DATA = "SENSOR_DATA";
    private static final String FORWARD_TO_B = "FORWARD_TO_B";
    private static final String DISPLAY = "DISPLAY";

    public static void main(String[] args) throws Exception {
        System.out.println(">>> Starting TwoIoT_Eavesdrop...");
        Log.setDisabled(false);

        CloudSim.init(1, Calendar.getInstance(), false);

        // 1) Build devices (no TopoFactory)
        FogDevice cloud   = createFogDevice("cloud",   40000, 16384, 10000, 10000, 0);
        FogDevice gateway = createFogDevice("gateway", 10000, 4096,   1000,  1000,  1);
        FogDevice iotA    = createFogDevice("iotA",     2000, 1024,   1000,  1000,  2);
        FogDevice iotB    = createFogDevice("iotB",     2000, 1024,   1000,  1000,  2);

        gateway.setParentId(cloud.getId());
        iotA.setParentId(gateway.getId());
        iotB.setParentId(gateway.getId());

        List<FogDevice> devices = Arrays.asList(cloud, gateway, iotA, iotB);

        // 2) Sensor on A and Actuator on B
        List<Sensor> sensors = new ArrayList<>();
        List<Actuator> actuators = new ArrayList<>();

        Sensor sensorA = new Sensor("sensorA", SENSOR_DATA, iotA.getId(), APP_ID, new DeterministicDistribution(5.0));
        sensors.add(sensorA);

        Actuator displayB = new Actuator("displayB", iotB.getId(), APP_ID, DISPLAY);
        actuators.add(displayB);

        // 3) Define the app graph
        Application app = createApp(APP_ID, WITH_ATTACKER);

        // 4) Placement: put modules on devices
        Map<String, List<String>> moduleToDevice = new HashMap<>();
        moduleToDevice.put("sender",    Collections.singletonList("iotA"));
        moduleToDevice.put("forwarder", Collections.singletonList("gateway"));
        moduleToDevice.put("receiver",  Collections.singletonList("iotB"));
        if (WITH_ATTACKER) {
            moduleToDevice.put("attacker", Collections.singletonList("gateway"));
        }

        // 5) Controller + submit (use the ctor with sensors & actuators)
        Controller controller = new Controller("master", devices, sensors, actuators);
        controller.submitApplication(
                app,
                0,
                new ModulePlacementEdgewards(app, devices, moduleToDevice, sensors, actuators)

        );

        // 6) (Optional) simple network links (latency ms, bandwidth)
        NetworkTopology.addLink(iotA.getId(), gateway.getId(), 2.0, 1000);
        NetworkTopology.addLink(iotB.getId(), gateway.getId(), 2.0, 1000);
        NetworkTopology.addLink(gateway.getId(), cloud.getId(), 5.0, 10000);

        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        System.out.println(">>> DONE. Sim finish time = " + CloudSim.clock());
    }

    private static Application createApp(String appId, boolean withAttacker) {
        Application app = Application.createApplication(appId, 1);

        // modules
        app.addAppModule("sender",    10, 1000);
        app.addAppModule("forwarder", 20, 1000);
        app.addAppModule("receiver",  10, 1000);
        if (withAttacker) app.addAppModule("attacker", 5, 512);

        // edges
        app.addAppEdge("sensorA", "sender", 1000, 100, SENSOR_DATA,
                AppEdge.SENSOR, AppEdge.UP, AppEdge.STATIC, 1);

        app.addAppEdge("sender", "forwarder", 1000, 500, SENSOR_DATA,
                AppEdge.MODULE, AppEdge.UP, AppEdge.STATIC, 1);

        app.addAppEdge("forwarder", "receiver", 1000, 200, FORWARD_TO_B,
                AppEdge.MODULE, AppEdge.DOWN, AppEdge.STATIC, 1);

        app.addAppEdge("receiver", "displayB", 500, 50, DISPLAY,
                AppEdge.ACTUATOR, AppEdge.DOWN, AppEdge.STATIC, 1);

        if (withAttacker) {
            app.addAppEdge("sender", "attacker", 1000, 1, SENSOR_DATA,
                    AppEdge.MODULE, AppEdge.UP, AppEdge.STATIC, 1);
        }

        // tuple mappings
        app.addTupleMapping("sender", SENSOR_DATA, SENSOR_DATA, new FractionalSelectivity(1.0));
        app.addTupleMapping("forwarder", SENSOR_DATA, FORWARD_TO_B, new FractionalSelectivity(1.0));
        // receiver triggers actuator via edge; attacker consumes only

        // loop for latency accounting
        app.setLoops(Collections.singletonList(
                new AppLoop(Arrays.asList("sender","forwarder","receiver"))
        ));
        return app;
    }

    /** Minimal device builder (no external TopoFactory). */
    private static FogDevice createFogDevice(String name, int mips, int ram, long upBw, long downBw, int level) {
        try {
            List<Pe> peList = new ArrayList<>();
            peList.add(new Pe(0, new PeProvisionerSimple(mips)));

            int hostId = FogUtils.generateEntityId();
            long storage = 1000000;
            int bw = 10000;

            PowerHost host = new PowerHost(
                    hostId,
                    new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw),
                    storage,
                    peList,
                    new VmSchedulerTimeSharedOverSubscription(peList),
                    new PowerModelLinear(87.53, 0.31)
            );

            List<PowerHost> hostList = new ArrayList<>();
            hostList.add(host);

            long ratePerMips = 1;
            FogDevice dev = new FogDevice(
                    name,
                    hostList,
                    new VmAllocationPolicySimple(hostList),
                    new LinkedList<Storage>(),
                    10.0,
                    upBw,
                    downBw,
                    0,
                    ratePerMips
            );
            dev.setLevel(level);
            return dev;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
