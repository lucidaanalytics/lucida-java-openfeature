package dev.lucida.openfeature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lucida.FlagContext;
import dev.lucida.FlagResult;
import dev.lucida.Lucida;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.TrackingEventDetails;
import dev.openfeature.sdk.Value;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenFeature server provider over the Lucida Java SDK. The targeting key is the Lucida distinct id;
 * every other context attribute becomes a person property, except the reserved ones: {@code groups}
 * (type to key), {@code group_properties}, {@code device_properties}, {@code geo_properties}, {@code device_id},
 * {@code app_version}, {@code app_contexts} and {@code now} (spec v2 evaluation inputs). Variants resolve to the
 * string value, payload flags to number/object evaluations. Flags are decided locally; the ones that need the
 * server (cohorts) are asked of it unless the client is local-only (see {@link Lucida#getFlagResult}).
 *
 * <p>Raw Lucida reasons map through {@code testdata/openfeature/reasons.json}; every evaluation carries flag metadata
 * ({@code lucidaReason}, {@code conditionIndex}, {@code requestId}, {@code evaluatedLocally}). A client fallback value
 * resolves with reason DEFAULT and no error code. Events: READY after the first load (initialize), CONFIGURATION_CHANGED on
 * a reload, STALE when a reload fails, ERROR when the first load fails.
 */
public final class LucidaProvider extends EventProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);
    private final Lucida client;
    private final AtomicReference<String> state = new AtomicReference<>("init"); // init -> ready | error

    public LucidaProvider(Lucida client) {
        this.client = client;
        client.onFlagsChange(this::changed);
        client.onFlagsStale(this::stale);
    }

    @Override
    public void initialize(EvaluationContext ctx) throws Exception {
        if (!client.awaitFlags(READY_TIMEOUT)) {
            state.set("error");
            throw new IllegalStateException("first flag definitions load failed");
        }
        state.set("ready");
    }

    void changed() {
        String was = state.getAndSet("ready");
        if ("error".equals(was)) emit(ProviderEvent.PROVIDER_READY, ProviderEventDetails.builder().message("flag definitions loaded").build());
        else if ("ready".equals(was)) emit(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, ProviderEventDetails.builder().message("flag definitions changed").build());
    }

    void stale() {
        if ("ready".equals(state.get())) emit(ProviderEvent.PROVIDER_STALE, ProviderEventDetails.builder().message("flag definitions reload failed").build());
    }

    /** OpenFeature tracking: a Lucida event named {@code name} for the targeting key; details become properties, the value is {@code $value}. */
    public void track(String name, EvaluationContext ctx, TrackingEventDetails details) {
        String id = ctx == null ? null : ctx.getTargetingKey();
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("targetingKey (distinct id) is required");
        Map<String, Object> props = new HashMap<>();
        if (details != null) {
            props.putAll(details.asObjectMap());
            details.getValue().ifPresent(v -> props.put("$value", v));
        }
        client.capture(id, name, props, new Lucida.Extras(toFlagContext(id, ctx).groups(), null, null, null, null));
    }

    @Override
    public Metadata getMetadata() {
        return () -> "lucida";
    }

    private static <T> ProviderEvaluation<T> fail(T def, ErrorCode code, String msg) {
        return ProviderEvaluation.<T>builder().value(def).reason(Reason.ERROR.toString()).errorCode(code).errorMessage(msg).build();
    }

    private static <T> ProviderEvaluation<T> ok(T value, FlagResult r) {
        String variant = r.value() instanceof String s ? s : r.variant();
        ImmutableMetadata.ImmutableMetadataBuilder meta = ImmutableMetadata.builder().addString("lucidaReason", r.reason() == null ? "" : r.reason());
        if (r.conditionIndex() >= 0) meta.addInteger("conditionIndex", r.conditionIndex());
        if (r.requestId() != null) meta.addString("requestId", r.requestId());
        meta.addBoolean("evaluatedLocally", r.evaluatedLocally());
        return ProviderEvaluation.<T>builder().value(value).variant(variant).reason(reason(r).toString()).flagMetadata(meta.build()).build();
    }

    /** Maps a raw Lucida reason to an OpenFeature one (table: testdata/openfeature/reasons.json); a matched variant string is a SPLIT. */
    static Reason reason(FlagResult r) {
        String raw = r.reason() == null ? "" : r.reason();
        return switch (raw) {
            case "condition_match" -> r.value() instanceof String ? Reason.SPLIT : Reason.TARGETING_MATCH;
            case "sticky_bucket" -> Reason.CACHED;
            case "no_condition_match", "out_of_rollout", "holdout", "namespace_excluded", "no_bucketing_key", "prerequisite_failed",
                    "prerequisite_missing", "fallback" -> Reason.DEFAULT;
            case "prerequisite_cycle" -> Reason.ERROR;
            case "inactive", "kill_switch", "app_context_excluded" -> Reason.DISABLED;
            case "preview", "user_preview" -> Reason.STATIC;
            case "remote" -> Reason.TARGETING_MATCH; // a gateway too old to send a reason
            default -> Reason.UNKNOWN;
        };
    }

    /** The payload of a read; a fallback value is its own payload (so int / double / object fallbacks work). */
    private static JsonNode payload(FlagResult r) {
        return r.isFallback() ? JSON.valueToTree(r.value()) : r.payload();
    }

    /** Thrown internally to carry an error evaluation out of {@link #resolve}. */
    private static final class Failure extends Exception {
        final ErrorCode code;

        Failure(ErrorCode code, String msg) {
            super(msg, null, false, false);
            this.code = code;
        }
    }

    private FlagResult resolve(String flag, EvaluationContext ctx) throws Failure {
        String id = ctx == null ? null : ctx.getTargetingKey();
        if (id == null || id.isEmpty()) throw new Failure(ErrorCode.TARGETING_KEY_MISSING, "targetingKey (distinct id) is required");
        FlagResult r = client.getFlagResult(flag, toFlagContext(id, ctx));
        if (r == null) throw new Failure(ErrorCode.FLAG_NOT_FOUND, "flag " + flag + " not found");
        return r;
    }

    private static final java.util.Set<String> RESERVED = java.util.Set.of("targetingKey", "groups", "group_properties", "groupProperties", "device_properties",
            "geo_properties", "device_id", "app_version", "app_contexts", "now");

    static FlagContext toFlagContext(String id, EvaluationContext ctx) {
        Map<String, Value> all = ctx.asMap();
        Map<String, JsonNode> person = new HashMap<>();
        all.forEach((k, v) -> {
            if (!RESERVED.contains(k)) person.put(k, JSON.valueToTree(v.asObject()));
        });
        Map<String, String> groups = null;
        if (all.containsKey("groups") && all.get("groups").isStructure()) {
            groups = new HashMap<>();
            for (Map.Entry<String, Value> e : all.get("groups").asStructure().asMap().entrySet()) groups.put(e.getKey(), e.getValue().asString());
        }
        Map<String, Map<String, JsonNode>> groupProps = null;
        for (String gk : List.of("group_properties", "groupProperties")) {
            if (groupProps == null && all.containsKey(gk) && all.get(gk).isStructure()) {
                groupProps = new HashMap<>();
                for (Map.Entry<String, Value> e : all.get(gk).asStructure().asMap().entrySet()) groupProps.put(e.getKey(), nodes(e.getValue()));
            }
        }
        List<String> appContexts = null;
        if (all.containsKey("app_contexts") && all.get("app_contexts").isList()) {
            appContexts = new ArrayList<>();
            for (Value v : all.get("app_contexts").asList()) appContexts.add(v.asString());
        }
        return new FlagContext(id, null, str(all, "device_id"), str(all, "app_version"), groups, person, groupProps, nodes(all.get("device_properties")),
                nodes(all.get("geo_properties")), null, str(all, "now"), appContexts);
    }

    private static String str(Map<String, Value> m, String k) {
        return m.containsKey(k) && m.get(k).isString() ? m.get(k).asString() : null;
    }

    private static Map<String, JsonNode> nodes(Value v) {
        if (v == null || !v.isStructure()) return null;
        Map<String, JsonNode> out = new HashMap<>();
        v.asStructure().asMap().forEach((k, x) -> out.put(k, JSON.valueToTree(x.asObject())));
        return out;
    }

    @Override
    public ProviderEvaluation<Boolean> getBooleanEvaluation(String flag, Boolean def, EvaluationContext ctx) {
        try {
            FlagResult r = resolve(flag, ctx);
            // a multivariate flag is on for any variant, mirroring isEnabled
            return ok(!Boolean.FALSE.equals(r.value()), r);
        } catch (Failure f) {
            return fail(def, f.code, f.getMessage());
        }
    }

    @Override
    public ProviderEvaluation<String> getStringEvaluation(String flag, String def, EvaluationContext ctx) {
        try {
            FlagResult r = resolve(flag, ctx);
            if (r.value() instanceof String s) return ok(s, r);
            return fail(def, ErrorCode.TYPE_MISMATCH, "flag " + flag + " has no string variant");
        } catch (Failure f) {
            return fail(def, f.code, f.getMessage());
        }
    }

    @Override
    public ProviderEvaluation<Integer> getIntegerEvaluation(String flag, Integer def, EvaluationContext ctx) {
        try {
            FlagResult r = resolve(flag, ctx);
            JsonNode p = payload(r);
            if (p == null || !p.isNumber() || p.doubleValue() != Math.rint(p.doubleValue()) || Math.abs(p.doubleValue()) > Integer.MAX_VALUE) {
                return fail(def, ErrorCode.TYPE_MISMATCH, "flag " + flag + " payload is not an integer");
            }
            return ok(p.intValue(), r);
        } catch (Failure f) {
            return fail(def, f.code, f.getMessage());
        }
    }

    @Override
    public ProviderEvaluation<Double> getDoubleEvaluation(String flag, Double def, EvaluationContext ctx) {
        try {
            FlagResult r = resolve(flag, ctx);
            JsonNode p = payload(r);
            if (p == null || !p.isNumber()) return fail(def, ErrorCode.TYPE_MISMATCH, "flag " + flag + " payload is not a number");
            return ok(p.doubleValue(), r);
        } catch (Failure f) {
            return fail(def, f.code, f.getMessage());
        }
    }

    @Override
    public ProviderEvaluation<Value> getObjectEvaluation(String flag, Value def, EvaluationContext ctx) {
        try {
            FlagResult r = resolve(flag, ctx);
            JsonNode p = payload(r);
            if (p == null || p.isNull() || p.isMissingNode()) return fail(def, ErrorCode.TYPE_MISMATCH, "flag " + flag + " has no payload");
            return ok(toValue(p), r);
        } catch (Failure f) {
            return fail(def, f.code, f.getMessage());
        }
    }

    static Value toValue(JsonNode n) {
        if (n.isBoolean()) return new Value(n.booleanValue());
        if (n.isNumber()) return new Value(n.doubleValue());
        if (n.isTextual()) return new Value(n.textValue());
        if (n.isArray()) {
            List<Value> l = new ArrayList<>();
            n.forEach(c -> l.add(toValue(c)));
            return new Value(l);
        }
        if (n.isObject()) {
            Map<String, Value> m = new HashMap<>();
            n.fields().forEachRemaining(e -> m.put(e.getKey(), toValue(e.getValue())));
            return new Value(new MutableStructure(m));
        }
        return new Value();
    }
}
