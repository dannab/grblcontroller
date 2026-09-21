package in.co.gorest.grblcontroller.events;

/** FluidNC machine name read from its $I/startup response. */
public class MachineNameEvent {

    private final String machineName;

    public MachineNameEvent(String machineName) {
        this.machineName = machineName;
    }

    public String getMachineName() {
        return machineName;
    }
}
