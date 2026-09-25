package io.github.jarremapper.pipeline;

import io.github.jarremapper.model.ClassInfo;
import io.github.jarremapper.model.MatchResult;
import io.github.jarremapper.model.MemberKey;
import io.github.jarremapper.model.MethodInfo;

/**
 * Callback the {@link RemapPipeline} uses to communicate with the GUI.
 *
 * <p>Implementations are expected to be thread-safe enough for the GUI
 * thread to receive updates from a background worker thread (the pipeline
 * uses {@code Platform.runLater} internally when called from JavaFX).</p>
 */
public interface PipelineCallback {

    /** Stage label + 0..1 progress fraction. */
    void onProgress(double fraction, String stage);

    /** Append a single log line. */
    void onLog(String message);

    /** Append a structured {@link MatchResult} row to the result table. */
    void onMatchResult(MatchResult result);

    /**
     * Ask the user to name an unmatched class.
     *
     * <p>If the user picks "Skip", the implementation must return {@code null}
     * (the pipeline will create an identity mapping in that case).</p>
     *
     * @param unmappedClass the class info of the unmatched obfuscated class
     * @param decompiledSource the decompiled Java source (may be {@code null}
     *        or empty if decompilation failed)
     */
    String askForName(ClassInfo unmappedClass, String decompiledSource);

    /** Returns {@code true} if the user wants the pipeline to abort. */
    boolean isCancelled();
}
