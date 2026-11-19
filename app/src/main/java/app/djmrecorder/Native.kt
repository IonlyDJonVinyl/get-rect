package app.djmrecorder

/** JNI bridge to cpp/djmusb.c. Engine = armed capture (arm..disarm); session = files on disk (recordStart..recordStop). */
object Native {
    init { System.loadLibrary("djmusb") }

    /** Starts capture (no files). [resume] = 1 re-attaches to a session that is still open (mixer replugged). */
    external fun nativeArm(
        fd: Int, epIn: Int, maxPktIn: Int, chIn: Int, epOut: Int, maxPktOut: Int, chOut: Int, rate: Int,
        stereoPair: Int, preSec: Int, stemsPre: Int, resume: Int, gapFrames: Long
    ): Int
    external fun nativeDisarm()
    /** -> [result (0 or -errno), pre-roll frames, engine frame count at start, stems pre-roll frames] */
    external fun nativeRecordStart(multiPath: String?, stereoPath: String, reqPreFrames: Int, rf64Limit: Long): LongArray
    /** -> [record frames, stereo file bytes, stems file bytes, errno of a failed write] */
    external fun nativeRecordStop(): LongArray
    external fun nativeRecFrames(): Long
    external fun nativeStats(): LongArray
    /** (record position frames, missing frames) pairs since the last call. */
    external fun nativeEvents(): LongArray
    /** (stream frame, expected counter, got counter) triplets; only fills when ch11/12 carry a sample counter. */
    external fun nativeCounterLog(): LongArray
    /** New 5-byte (low, mid, high, left, right) 10 ms wave entries since the last call (sqrt-encoded peaks). */
    external fun nativeWave(): ByteArray
    /** Live monitoring tap: 1 starts filling the native ring, 0 stops. */
    external fun nativeMonitor(on: Int)
    /** Copies up to maxFrames interleaved stereo float frames; skips ahead if more than maxBacklog frames queued. */
    external fun nativeMonitorRead(out: FloatArray, maxFrames: Int, maxBacklog: Int): Int

    // nativeStats() layout
    const val S_FRAMES = 0; const val S_URBS = 1; const val S_PKT_ERR = 2; const val S_ODD = 3
    const val S_OUT_URBS = 4; const val S_OUT_ERR = 5; const val S_ERRNO = 6; const val S_RUNNING = 7
    const val S_PEAK0 = 8
    const val S_REC_FRAMES = 24; const val S_REC_ACTIVE = 25; const val S_REC_ERR = 26
    const val S_GAP_EVENTS = 27; const val S_GAP_FRAMES = 28; const val S_DEV_GONE = 29
    const val S_SF_JUMPS = 30; const val S_SF_REJECTED = 31; const val S_Q_LOST = 32
    const val S_STEREO_BYTES = 33; const val S_STEMS_BYTES = 34
    const val S_PRE_S = 35; const val S_PRE_M = 36
    const val S_CTR_VALID = 37; const val S_CTR_MISSING = 38; const val S_CTR_MISMATCH = 39
    const val S_MODE = 40; const val S_SF_LEARNED = 41

    private const val STEMS_PRE_CAP_BYTES = 32L * 1024 * 1024

    /** Longest stems (12 ch) pre-roll that fits the 32 MB cap, in seconds (rounded down). */
    fun stemsPreMaxSec(rate: Int, channels: Int = 12): Int = (STEMS_PRE_CAP_BYTES / (channels * 3) / rate).toInt()
}
