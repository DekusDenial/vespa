// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.restapi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.jdisc.http.HttpRequest.Method;
import com.yahoo.slime.Slime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rest API routing and response serialization
 *
 * @author bjorncs
 */
public class RestApi {

    private final Route defaultRoute;
    private final List<Route> routes;
    private final Map<Class<? extends Exception>, ExceptionMapper<?>> exceptionMappers;
    private final Map<Class<?>, ResponseMapper<?>> responseMappers;

    private RestApi(Builder builder) {
        this.defaultRoute = builder.defaultRoute != null ? builder.defaultRoute : createDefaultRoute();
        this.routes = List.copyOf(builder.routes);
        this.exceptionMappers = combineWithDefaultExceptionMappers(builder.exceptionMappers);
        this.responseMappers = combineWithDefaultResponseMappers(builder.responseMappers);
    }

    public HttpResponse handleRequest(HttpRequest request) {
        Path pathMatcher = new Path(request.getUri());
        Route resolvedRoute = resolveRoute(pathMatcher);
        MethodHandler<?> resolvedHandler = resolvedRoute.handlerPerMethod.get(request.getMethod());
        if (resolvedHandler == null) {
            resolvedHandler = resolvedRoute.defaultHandler;
        }
        RequestContext context = new RequestContextImpl(request, pathMatcher);
        Object entity;
        try {
            entity = resolvedHandler.handleRequest(context);
        } catch (RuntimeException e) {
            ExceptionMapper<RuntimeException> mapper = resolveExceptionMapper(e);
            if (mapper == null) throw e;
            return mapper.toResponse(e, context);
        }
        if (entity == null) throw new NullPointerException("Handler must return non-null value");
        ResponseMapper<Object> mapper = resolveResponseMapper(entity);
        if (mapper == null) throw new IllegalStateException("No mapper configured for " + entity.getClass());
        return mapper.toHttpResponse(entity, context);
    }

    private Route resolveRoute(Path pathMatcher) {
        Route matchingRoute = routes.stream()
                .filter(route -> pathMatcher.matches(route.pathPattern))
                .findFirst()
                .orElse(null);
        if (matchingRoute != null) return matchingRoute;
        pathMatcher.matches(defaultRoute.pathPattern); // to populate any path parameters
        return defaultRoute;
    }

    @SuppressWarnings("unchecked")
    private ExceptionMapper<RuntimeException> resolveExceptionMapper(RuntimeException e) {
        for (var entry : exceptionMappers.entrySet()) {
            if (entry.getKey().isAssignableFrom(e.getClass())) {
                return (ExceptionMapper<RuntimeException>) entry.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private ResponseMapper<Object> resolveResponseMapper(Object entity) {
        for (var entry : responseMappers.entrySet()) {
            if (entry.getKey().isAssignableFrom(entity.getClass())) {
                return (ResponseMapper<Object>) entry.getValue();
            }
        }
        return null;
    }

    private static Route createDefaultRoute() {
        return new Route.Builder("{*}")
                .defaultHandler(context -> { throw new NotFoundException(); })
                .build();
    }

    private static Map<Class<? extends Exception>, ExceptionMapper<?>> combineWithDefaultExceptionMappers(
            Map<Class<? extends Exception>, ExceptionMapper<?>> configuredExceptionMappers) {
        Map<Class<? extends Exception>, ExceptionMapper<?>> exceptionMappers = new LinkedHashMap<>();
        configuredExceptionMappers.forEach(exceptionMappers::put);
        if (!exceptionMappers.containsKey(RestApiException.class)) {
            ExceptionMapper<RestApiException> mapper = (exception, context) -> exception.response;
            exceptionMappers.put(RestApiException.class, mapper);
        }
        return exceptionMappers;
    }

    private static Map<Class<?>, ResponseMapper<?>> combineWithDefaultResponseMappers(
            Map<Class<?>, ResponseMapper<?>> configuredResponseMappers) {
        Map<Class<?>, ResponseMapper<?>> responseMappers = new LinkedHashMap<>();
        configuredResponseMappers.forEach(responseMappers::put);
        if (!responseMappers.containsKey(HttpResponse.class)) {
            ResponseMapper<HttpResponse> mapper = (entity, context) -> entity;
            responseMappers.put(HttpResponse.class, mapper);
        }
        if (!responseMappers.containsKey(Slime.class)) {
            ResponseMapper<Slime> mapper = (entity, context) -> new SlimeJsonResponse(entity);
            responseMappers.put(Slime.class, mapper);
        }
        if (!responseMappers.containsKey(JacksonResponseEntity.class)) {
            ResponseMapper<JacksonResponseEntity> mapper = (entity, context) -> new JacksonJsonResponse<>(200, entity, true);
            responseMappers.put(JacksonResponseEntity.class, mapper);
        }
        return responseMappers;
    }

    @FunctionalInterface public interface ExceptionMapper<EXCEPTION extends RuntimeException> { HttpResponse toResponse(EXCEPTION exception, RequestContext context); }

    @FunctionalInterface public interface MethodHandler<ENTITY> { ENTITY handleRequest(RequestContext context) throws RestApiException; }

    @FunctionalInterface public interface ResponseMapper<ENTITY> { HttpResponse toHttpResponse(ENTITY responseEntity, RequestContext context); }

    /** Marker interface required for automatic serialization of Jackson response entities */
    public interface JacksonResponseEntity {}

    public interface RequestContext {
        HttpRequest request();
        PathParameters pathParameters();

        interface PathParameters {
            Optional<String> getString(String name);
        }
    }

    private static class RequestContextImpl implements RequestContext, RequestContext.PathParameters {

        private final HttpRequest request;
        private final Path pathMatcher;

        private RequestContextImpl(HttpRequest request, Path pathMatcher) {
            this.request = request;
            this.pathMatcher = pathMatcher;
        }

        @Override public HttpRequest request() { return request; }
        @Override public PathParameters pathParameters() { return this; }
        @Override public Optional<String> getString(String name) { return Optional.ofNullable(pathMatcher.get(name)); }
    }

    public static class Builder {

        private final List<Route> routes = new ArrayList<>();
        private final Map<Class<? extends Exception>, ExceptionMapper<?>> exceptionMappers = new LinkedHashMap<>();
        private final Map<Class<?>, ResponseMapper<?>> responseMappers = new LinkedHashMap<>();
        private Route defaultRoute;

        public Builder setDefaultRoute(Route route) { this.defaultRoute = route; return this; }

        public Builder addRoute(Route route) { routes.add(route); return this; }

        public <EXCEPTION extends RuntimeException> Builder addExceptionMapper(Class<EXCEPTION> type, ExceptionMapper<EXCEPTION> mapper) {
            exceptionMappers.put(type, mapper); return this;
        }

        public <ENTITY> Builder addResponseMapper(Class<ENTITY> type, ResponseMapper<ENTITY> mapper) {
            responseMappers.put(type, mapper); return this;
        }

        public RestApi build() { return new RestApi(this); }

    }

    public static class Route {
        private final String pathPattern;
        private final Map<Method, MethodHandler<?>> handlerPerMethod;
        private final MethodHandler<?> defaultHandler;

        private Route(Builder builder) {
            this.pathPattern = builder.pathPattern;
            this.handlerPerMethod = Map.copyOf(builder.handlerPerMethod);
            this.defaultHandler = builder.defaultHandler != null ? builder.defaultHandler : createDefaultMethodHandler();
        }

        private static MethodHandler<?> createDefaultMethodHandler() {
            return context -> { throw new MethodNotAllowedException(context.request()); };
        }

        public static class Builder {
            private final String pathPattern;
            private final Map<Method, MethodHandler<?>> handlerPerMethod = new HashMap<>();
            private MethodHandler<?> defaultHandler;

            public Builder(String pathPattern) {
                this.pathPattern = pathPattern;
            }

            public Builder get(MethodHandler<?> handler) { return addHandler(Method.GET, handler); }
            public Builder post(MethodHandler<?> handler) { return addHandler(Method.POST, handler); }
            public Builder put(MethodHandler<?> handler) { return addHandler(Method.PUT, handler); }
            public Builder delete(MethodHandler<?> handler) { return addHandler(Method.DELETE, handler); }
            public Builder defaultHandler(MethodHandler<?> handler) { defaultHandler = handler; return this; }

            private Builder addHandler(Method method, MethodHandler<?> handler) {
                handlerPerMethod.put(method, handler); return this;
            }

            public Route build() { return new Route(this); }

        }
    }

    public static class RestApiException extends RuntimeException {
        private final HttpResponse response;

        public RestApiException(int responseCode, String message) {
            super(message);
            this.response = createDefaultResponse(responseCode, message);
        }

        public RestApiException(HttpResponse response, String message) {
            super(message);
            this.response = response;
        }

        public RestApiException(int responseCode, String message, Throwable cause) {
            super(message, cause);
            this.response = createDefaultResponse(responseCode, message);
        }

        public RestApiException(HttpResponse response, String message, Throwable cause) {
            super(message, cause);
            this.response = response;
        }

        private static HttpResponse createDefaultResponse(int code, String message) {
            ObjectNode json = JacksonJsonMapper.instance.createObjectNode()
                    .put("code", code)
                    .put("message", message);
            return new JacksonJsonResponse<>(code, json, true);
        }
    }

    public static class NotFoundException extends RestApiException {
        public NotFoundException() { super(404, "Not Found"); }
    }

    public static class MethodNotAllowedException extends RestApiException {
        public MethodNotAllowedException() { super(405, "Method Not Allowed"); }

        public MethodNotAllowedException(HttpRequest request) {
            super(405, "Method '" + request.getMethod().name() + "' is not allowed");
        }
    }

}
