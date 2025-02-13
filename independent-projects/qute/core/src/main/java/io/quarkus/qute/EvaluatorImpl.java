package io.quarkus.qute;

import io.quarkus.qute.Expression.Part;
import io.quarkus.qute.ExpressionImpl.PartImpl;
import io.quarkus.qute.Results.NotFound;
import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletionStage;
import org.jboss.logging.Logger;

class EvaluatorImpl implements Evaluator {

    private static final Logger LOGGER = Logger.getLogger(EvaluatorImpl.class);

    private final List<ValueResolver> resolvers;
    private final Map<String, List<NamespaceResolver>> namespaceResolvers;
    private final boolean strictRendering;

    EvaluatorImpl(List<ValueResolver> valueResolvers, List<NamespaceResolver> namespaceResolvers, boolean strictRendering) {
        this.resolvers = valueResolvers;
        Map<String, List<NamespaceResolver>> namespaceResolversMap = new HashMap<>();
        for (NamespaceResolver namespaceResolver : namespaceResolvers) {
            List<NamespaceResolver> matching = namespaceResolversMap.get(namespaceResolver.getNamespace());
            if (matching == null) {
                matching = new ArrayList<>();
                namespaceResolversMap.put(namespaceResolver.getNamespace(), matching);
            }
            matching.add(namespaceResolver);
        }
        for (Entry<String, List<NamespaceResolver>> entry : namespaceResolversMap.entrySet()) {
            List<NamespaceResolver> list = entry.getValue();
            if (list.size() == 1) {
                entry.setValue(Collections.singletonList(list.get(0)));
            } else {
                // Sort by priority - higher priority wins
                list.sort(Comparator.comparingInt(WithPriority::getPriority).reversed());
                entry.setValue(List.copyOf(list));
            }
        }
        this.namespaceResolvers = namespaceResolversMap;
        this.strictRendering = strictRendering;
    }

    @Override
    public CompletionStage<Object> evaluate(Expression expression, ResolutionContext resolutionContext) {
        Iterator<Part> parts;
        if (expression.hasNamespace()) {
            parts = expression.getParts().iterator();
            List<NamespaceResolver> matching = namespaceResolvers.get(expression.getNamespace());
            if (matching == null) {
                return CompletedStage.failure(new TemplateException(expression.getOrigin(),
                        String.format("No namespace resolver found for [%s] in expression {%s} in template %s on line %s",
                                expression.getNamespace(), expression.toOriginalString(),
                                expression.getOrigin().getTemplateId(), expression.getOrigin().getLine())));
            }
            EvalContext context = new EvalContextImpl(false, null, resolutionContext, parts.next());
            if (matching.size() == 1) {
                // Very often a single matching resolver will be found
                return matching.get(0).resolve(context).thenCompose(r -> {
                    if (parts.hasNext()) {
                        return resolveReference(false, r, parts, resolutionContext, expression, 0);
                    } else {
                        return toCompletionStage(r);
                    }
                });
            } else {
                // Multiple namespace resolvers match
                return resolveNamespace(context, resolutionContext, parts, matching.iterator(), expression);
            }
        } else {
            if (expression.isLiteral()) {
                return expression.asLiteral();
            } else {
                parts = expression.getParts().iterator();
                return resolveReference(true, resolutionContext.getData(), parts, resolutionContext, expression, 0);
            }
        }
    }

    private CompletionStage<Object> resolveNamespace(EvalContext context, ResolutionContext resolutionContext,
            Iterator<Part> parts, Iterator<NamespaceResolver> resolvers, Expression expression) {
        // Use the next matching namespace resolver
        NamespaceResolver resolver = resolvers.next();
        return resolver.resolve(context).thenCompose(r -> {
            if (Results.isNotFound(r)) {
                // Result not found
                if (resolvers.hasNext()) {
                    // Try the next matching resolver
                    return resolveNamespace(context, resolutionContext, parts, resolvers, expression);
                } else {
                    // No other matching namespace resolver exist
                    if (parts.hasNext()) {
                        // Continue to the next part of the expression
                        return resolveReference(false, r, parts, resolutionContext, expression, 0);
                    } else if (strictRendering) {
                        throw propertyNotFound(r, expression);
                    }
                    return Results.notFound(context);
                }
            } else if (parts.hasNext()) {
                return resolveReference(false, r, parts, resolutionContext, expression, 0);
            } else {
                return toCompletionStage(r);
            }
        });
    }

    private CompletionStage<Object> resolveReference(boolean tryParent, Object ref, Iterator<Part> parts,
            ResolutionContext resolutionContext, final Expression expression, int partIndex) {
        Part part = parts.next();
        EvalContextImpl evalContext = new EvalContextImpl(tryParent, ref, resolutionContext, part);
        if (!parts.hasNext()) {
            // The last part - no need to compose
            return resolve(evalContext, null, true, expression, true, partIndex);
        } else {
            // Next part - no need to try the parent context/outer scope
            return resolve(evalContext, null, true, expression, false, partIndex)
                    .thenCompose(r -> resolveReference(false, r, parts, resolutionContext, expression, partIndex + 1));
        }
    }

    private CompletionStage<Object> resolve(EvalContextImpl evalContext, Iterator<ValueResolver> resolvers,
            boolean tryCachedResolver, final Expression expression, boolean isLastPart, int partIndex) {

        if (tryCachedResolver) {
            // Try the cached resolver first
            ValueResolver cachedResolver = evalContext.getCachedResolver();
            if (cachedResolver != null && cachedResolver.appliesTo(evalContext)) {
                return cachedResolver.resolve(evalContext).thenCompose(r -> {
                    if (Results.isNotFound(r)) {
                        return resolve(evalContext, null, false, expression, isLastPart, partIndex);
                    } else {
                        return toCompletionStage(r);
                    }
                });
            }
        }

        if (resolvers == null) {
            // Iterate the resolvers lazily
            resolvers = this.resolvers.iterator();
        }

        ValueResolver applicableResolver = null;
        while (applicableResolver == null && resolvers.hasNext()) {
            ValueResolver next = resolvers.next();
            if (next.appliesTo(evalContext)) {
                applicableResolver = next;
            }
        }
        if (applicableResolver == null) {
            ResolutionContext parent = evalContext.resolutionContext.getParent();
            if (evalContext.tryParent && parent != null) {
                // Continue with parent context
                return resolve(
                        new EvalContextImpl(true, parent.getData(), parent,
                                evalContext.part),
                        null, false, expression, isLastPart, partIndex);
            }
            LOGGER.tracef("Unable to resolve %s", evalContext);
            Object notFound;
            if (Results.isNotFound(evalContext.getBase())) {
                // If the base is "not found" then just return it
                notFound = evalContext.getBase();
            } else {
                // If the next part matches the ValueResolvers.orResolver() we can just use the empty NotFound constant
                // and avoid unnecessary allocations
                // This optimization should be ok in 99% of cases, for the rest an incomplete NotFound is an acceptable loss
                Part nextPart = isLastPart ? null : expression.getParts().get(partIndex + 1);
                if (nextPart != null
                        // is virtual method with a single param
                        && nextPart.isVirtualMethod()
                        && nextPart.asVirtualMethod().getParameters().size() == 1
                        // name has less than 3 chars
                        && nextPart.getName().length() < 3
                        // name is "?:", "or" or ":"
                        && (nextPart.getName().equals(ValueResolvers.ELVIS)
                                || nextPart.getName().equals(ValueResolvers.OR)
                                || nextPart.getName().equals(ValueResolvers.COLON))) {
                    notFound = Results.NotFound.EMPTY;
                } else {
                    notFound = Results.NotFound.from(evalContext);
                }
            }
            // If in strict mode then just throw an exception
            if (strictRendering && isLastPart) {
                throw propertyNotFound(notFound, expression);
            }
            return CompletedStage.of(notFound);
        }

        final Iterator<ValueResolver> remainingResolvers = resolvers;
        final ValueResolver foundResolver = applicableResolver;
        return applicableResolver.resolve(evalContext).thenCompose(r -> {
            if (Results.isNotFound(r)) {
                // Result not found - try the next resolver
                return resolve(evalContext, remainingResolvers, false, expression, isLastPart, partIndex);
            } else {
                // Cache the first resolver where a result is found
                evalContext.setCachedResolver(foundResolver);
                return toCompletionStage(r);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static CompletionStage<Object> toCompletionStage(Object result) {
        if (result instanceof CompletionStage) {
            // If the result is a completion stage return it as is
            return (CompletionStage<Object>) result;
        } else if (result instanceof Uni) {
            // Subscribe to the Uni
            return ((Uni<Object>) result).subscribeAsCompletionStage();
        }
        return CompletedStage.of(result);
    }

    private static TemplateException propertyNotFound(Object result, Expression expression) {
        String propertyMessage;
        if (result instanceof NotFound) {
            propertyMessage = ((NotFound) result).asMessage();
        } else {
            propertyMessage = "Property not found";
        }
        return new TemplateException(expression.getOrigin(),
                String.format("%s in expression {%s} in template %s on line %s", propertyMessage, expression.toOriginalString(),
                        expression.getOrigin().getTemplateId(), expression.getOrigin().getLine()));
    }

    static class EvalContextImpl implements EvalContext {

        final boolean tryParent;
        final Object base;
        final ResolutionContext resolutionContext;
        final PartImpl part;
        final List<Expression> params;
        final String name;

        EvalContextImpl(boolean tryParent, Object base, ResolutionContext resolutionContext, Part part) {
            this.tryParent = tryParent;
            this.base = base;
            this.resolutionContext = resolutionContext;
            this.part = (PartImpl) part;
            this.name = part.getName();
            this.params = part.isVirtualMethod() ? part.asVirtualMethod().getParameters() : Collections.emptyList();
        }

        @Override
        public Object getBase() {
            return base;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public List<Expression> getParams() {
            return params;
        }

        @Override
        public CompletionStage<Object> evaluate(String value) {
            return evaluate(ExpressionImpl.from(value));
        }

        @Override
        public CompletionStage<Object> evaluate(Expression expression) {
            return resolutionContext.evaluate(expression);
        }

        @Override
        public Object getAttribute(String key) {
            return resolutionContext.getAttribute(key);
        }

        ValueResolver getCachedResolver() {
            return part.cachedResolver;
        }

        void setCachedResolver(ValueResolver valueResolver) {
            // Non-atomic write is ok here
            part.cachedResolver = valueResolver;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("EvalContextImpl [tryParent=").append(tryParent).append(", base=").append(base).append(", name=")
                    .append(getName()).append(", params=").append(getParams()).append("]");
            return builder.toString();
        }

    }

}
