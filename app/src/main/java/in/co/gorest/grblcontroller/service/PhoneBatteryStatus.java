package in.co.gorest.grblcontroller.service;

/** Immutable cached snapshot: HTTP readers never query Android's battery service. */
final class PhoneBatteryStatus {
    static final PhoneBatteryStatus UNKNOWN = new PhoneBatteryStatus(-1, false, false);

    final int percent;
    final boolean charging;
    final boolean plugged;

    private PhoneBatteryStatus(int percent, boolean charging, boolean plugged) {
        this.percent = percent;
        this.charging = charging;
        this.plugged = plugged;
    }

    static PhoneBatteryStatus fromValues(int level, int scale, boolean present,
                                         boolean charging, boolean plugged) {
        if (!present || level < 0 || scale <= 0) return UNKNOWN;
        int percent = (int) Math.min(100L, Math.round(level * 100.0 / scale));
        return new PhoneBatteryStatus(percent, charging, plugged);
    }

    boolean isLow() {
        return percent >= 0 && percent <= 20;
    }

    String displayText() {
        if (percent < 0) return "Batteria telefono: non disponibile";
        return "Batteria telefono: " + percent + "%"
                + (charging ? " · in carica" : plugged ? " · alimentazione collegata" : "")
                + (isLow() ? " · ATTENZIONE: batteria scarica" : "");
    }

    String jsonFields() {
        return "\"batteryPercent\":" + (percent < 0 ? "null" : percent)
                + ",\"batteryCharging\":" + charging
                + ",\"batteryPlugged\":" + plugged;
    }
}
