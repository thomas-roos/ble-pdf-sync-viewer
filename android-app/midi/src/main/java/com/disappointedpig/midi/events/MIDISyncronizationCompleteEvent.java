package com.disappointedpig.midi.events;

import android.os.Bundle;

public class MIDISyncronizationCompleteEvent {

    public final Bundle rinfo;

    public MIDISyncronizationCompleteEvent(final Bundle r) {
        rinfo = r;
    }

}
