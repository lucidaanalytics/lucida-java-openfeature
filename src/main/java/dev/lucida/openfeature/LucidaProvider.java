package dev.lucida.openfeature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lucida.FlagContext;
import dev.lucida.FlagResult;
import dev.lucida.Lucida;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
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
 */
public final class LucidaProvider implements FeatureProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Lucida client;

    public LucidaProvider(Lucida client) {
        this.client = client;
    }

    @Override
    public Metadata getMetadata() {
        return () -> "lucida";
    }

    private static <T> ProviderEvaluation<T> fail(T def, ErrorCode code, String msg) {
        return ProviderEvaluation.<T>builder().value(def).reason(Reason.ERROR.toString()).errorCode(code).errorMessage(msg).build();
    }

    private static <T> ProviderEvaluation<T> ok(T value, FlagResult r) {
        String variant = r.value() instanceof String s ? s : null;
        return ProviderEvaluation.<T>builder().value(value).variant(variant).reason(reason(r).toString()).build();
    }

    /** Maps a Lucida evaluation reason to an OpenFeature one. */
    static Reason reason(FlagResult r) {
        return switch (r.reason()) {
            case "inactive", "kill_switch", "app_context_excluded" -> Reason.DISABLED;
            case "condition_match", "sticky_bucket", "remote" -> Reason.TARGETING_MATCH;
            default -> Reason.DEFAULT; // no condition matched, out of rollout, holdout, prerequisite failed...
        };
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

    private static final java.util.Set<String> RESERVED = java.util.Set.of("targetingKey", "groups", "group_properties", "device_properties",
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
        if (all.containsKey("group_properties") && all.get("group_properties").isStructure()) {
            groupProps = new HashMap<>();
            for (Map.Entry<String, Value> e : all.get("group_properties").asStructure().asMap().entrySet()) groupProps.put(e.getKey(), nodes(e.getValue()));
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
            JsonNode p = r.payload();
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
            JsonNode p = r.payload();
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
            JsonNode p = r.payload();
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
