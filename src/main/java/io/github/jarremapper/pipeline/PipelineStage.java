package io.github.jarremapper.pipeline;

/**
 * One stage of the {@link RemapPipeline}.
 *
 * <p>Implementations read their inputs from {@link PipelineContext} and
 * write their outputs back to it. Stages are run sequentially by the
 * pipeline, but a stage may use parallel streams internally as long as
 * it serializes any side effects that need to be ordered (e.g. progress
 * reporting, table-row insertion).</p>
 *
 * <p>The contract:</p>
 * <ul>
 *   <li>{@link #name()} — short, GUI-friendly stage label.</li>
 *   <li>{@link #run(PipelineContext)} — perform the stage's work;
 *       call {@link PipelineCallback#onProgress} with a fraction
 *       in {@code [0, 1]} as appropriate.</li>
 *   <li>Throw {@link InterruptedException} (or set the thread interrupted
 *       flag) when the user cancels — checked at every iteration.</li>
 *   <li>Be idempotent in the sense that re-running after a clean
 *       failure should not corrupt state.</li>
 * </ul>
 */
public interface PipelineStage {

    /** Short, GUI-friendly stage label (e.g. {@code "Parse target JAR"}). */
    String name();

    /**
     * Run this stage to completion (or throw).
     *
     * @throws Exception if the stage fails — the pipeline reports the
     *         error via the callback and stops.
     * @throws InterruptedException if the user cancelled.
     */
    void run(PipelineContext ctx) throws Exception;
}
