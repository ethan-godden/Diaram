package com.github.ethangodden.diaram.core;

import com.github.ethangodden.diaram.model.MemorySnapshot;
import com.github.ethangodden.diaram.model.diff.MemoryDiff;

/** Implemented by the view; every method is invoked on the SWT UI thread. */
public interface ISnapshotConsumer {

    void snapshotReady(MemorySnapshot snapshot, MemoryDiff diff);

    /** The rendered thread resumed: gray out the current diagram, keep it visible. */
    void threadResumed(String threadToken);

    /** No usable context (no session, non-Java debugger, terminated): show placeholder. */
    void cleared(String reason);
}
