package com.trs.simulation.agent;

import com.trs.simulation.SimulationTypes.AgentDefinition;
import com.trs.simulation.SimulationTypes.TaskRequest;
import com.trs.simulation.model.ModelService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Shares model concurrency limits and reserves budget through the runtime host. @author czhcc-trsdsj\codexsandboxoffline */
final class ModelInvocationGuard {
    private final ModelService models;
    private final Map<String, Semaphore> limits = new ConcurrentHashMap<>();

    ModelInvocationGuard(ModelService models) {
        this.models = models;
    }

    Model model(
            HierarchyHost host,
            AgentDefinition agent,
            String taskId,
            boolean master,
            TaskRequest task,
            List<AgentDefinition> children,
            UnaryOperator<List<ToolSchema>> offerTools) {
        var config = models.get(agent.modelId());
        Model delegate = config.baseUrl().startsWith("mock:")
                ? new HierarchyMockModel(host, agent, children, master, task)
                : OpenAIChatModel.builder()
                        .modelName(config.modelName())
                        .baseUrl(config.baseUrl())
                        .apiKey(models.credential(config))
                        .stream(false)
                        .nativeStructuredOutput(false)
                        .generateOptions(GenerateOptions.builder()
                                .temperature(config.temperature())
                                .maxTokens(config.maxTokens())
                                .build())
                        .build();
        return new Model() {
            /** Reserves a real request and releases concurrency before any nested tool execution. @author czhcc-trsdsj\codexsandboxoffline */
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(() -> {
                    host.check();
                    var semaphore = limits.computeIfAbsent(
                            config.id() + ":" + config.concurrency(), key -> new Semaphore(config.concurrency()));
                    var requestTools = offerTools.apply(tools);
                    var acquired = new AtomicBoolean();
                    var finished = new AtomicBoolean();
                    Runnable release = () -> {
                        if (acquired.getAndSet(false)) semaphore.release();
                    };
                    return Mono.fromCallable(() -> {
                                if (!semaphore.tryAcquire(config.timeoutSeconds(), TimeUnit.SECONDS))
                                    throw new IllegalStateException("Model concurrency timeout");
                                acquired.set(true);
                                if (finished.get()) {
                                    release.run();
                                    throw new CancellationException("Model request cancelled before acquisition");
                                }
                                host.check();
                                host.beforeModel(agent, taskId, config.maxTokens());
                                return true;
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(ignored -> delegate.stream(messages, requestTools, options)
                                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                                    .collectList()
                                    .doOnSuccess(value -> release.run())
                                    .flatMapMany(Flux::fromIterable))
                            .doFinally(signal -> {
                                finished.set(true);
                                release.run();
                            });
                });
            }
            /** Preserves simulated provenance and SDK model metadata. @author czhcc-trsdsj\codexsandboxoffline */
            @Override
            public String getModelName() {
                return delegate.getModelName();
            }
        };
    }
}
