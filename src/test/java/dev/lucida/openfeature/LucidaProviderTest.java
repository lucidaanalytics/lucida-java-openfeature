package dev.lucida.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lucida.FlagContext;
import dev.lucida.FlagResult;
import dev.lucida.Lucida;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LucidaProviderTest {
    private static final String FIXTURE = "{\"flags\":["
            + "{\"key\":\"on\",\"type\":\"boolean\",\"active\":true,\"salt\":\"s\",\"conditions\":[{\"filters\":[],\"rollout_pct\":100}]},"
            + "{\"key\":\"color\",\"type\":\"multivariate\",\"active\":true,\"salt\":\"s\",\"conditions\":[{\"filters\":[],\"rollout_pct\":100}],"
            + "\"variants\":[{\"key\":\"blue\",\"rollout_pct\":100,\"payload\":{\"hex\":\"#00f\"}}]},"
            + "{\"key\":\"limit\",\"type\":\"json\",\"active\":true,\"salt\":\"s\",\"payload\":5,\"conditions\":[{\"filters\":[],\"rollout_pct\":100}]},"
            + "{\"key\":\"ver\",\"type\":\"boolean\",\"active\":true,\"salt\":\"s\",\"conditions\":[{\"filters\":[{\"type\":\"app_version\",\"op\":\"semver_caret\",\"value\":\"1.2.3\"}],\"rollout_pct\":100}]},"
            + "{\"key\":\"web\",\"type\":\"boolean\",\"active\":true,\"salt\":\"s\",\"app_contexts\":[\"web\"],\"conditions\":[{\"filters\":[],\"rollout_pct\":100}]},"
            + "{\"key\":\"off\",\"type\":\"boolean\",\"active\":false,\"salt\":\"s\",\"conditions\":[]},"
            + "{\"key\":\"prefix\",\"type\":\"boolean\",\"active\":true,\"salt\":\"s\",\"conditions\":[{\"filters\":[{\"type\":\"person\",\"key\":\"email\",\"op\":\"ends_with\",\"value\":\"@acme.io\"}],\"rollout_pct\":100}]},"
            + "{\"key\":\"ratio\",\"type\":\"json\",\"active\":true,\"salt\":\"s\",\"payload\":1.5,\"conditions\":[{\"filters\":[],\"rollout_pct\":100}]}"
            + "]}";

    private static Lucida lucida;
    private static Client client;
    private static LucidaProvider provider;

    @BeforeAll
    static void setUp() throws Exception {
        Lucida.Options o = new Lucida.Options();
        o.testModeFixtureJson = FIXTURE;
        o.fallbacks = Map.of("dark", true, "cap", "ignored");
        lucida = new Lucida("lc_pk_x", o);
        provider = new LucidaProvider(lucida);
        OpenFeatureAPI.getInstance().setProviderAndWait(provider);
        client = OpenFeatureAPI.getInstance().getClient();
    }

    @AfterAll
    static void tearDown() {
        OpenFeatureAPI.getInstance().shutdown();
        lucida.close();
    }

    private static ImmutableContext ctx() {
        return new ImmutableContext("u1");
    }

    @Test
    void booleanAndVariant() {
        FlagEvaluationDetails<Boolean> on = client.getBooleanDetails("on", false, ctx());
        assertTrue(on.getValue());
        assertEquals("TARGETING_MATCH", on.getReason());
        assertTrue(client.getBooleanValue("color", false, ctx())); // any variant is on

        FlagEvaluationDetails<String> color = client.getStringDetails("color", "none", ctx());
        assertEquals("blue", color.getValue());
        assertEquals("blue", color.getVariant());
    }

    @Test
    void specV2ContextAndReasons() {
        ImmutableContext v2 = new ImmutableContext("u1", java.util.Map.of("app_version", new Value("1.9.0"), "email", new Value("a@acme.io")));
        assertTrue(client.getBooleanValue("ver", false, v2));
        assertTrue(client.getBooleanValue("prefix", false, v2));
        assertFalse(client.getBooleanValue("ver", false, new ImmutableContext("u1", java.util.Map.of("app_version", new Value("2.0.0")))));
        assertFalse(client.getBooleanValue("prefix", false, new ImmutableContext("u1", java.util.Map.of("email", new Value("a@other.io")))));
        assertEquals("DISABLED", client.getBooleanDetails("off", true, ctx()).getReason());
        // app contexts: a web-only flag is dropped for an api request, served for a web one
        ImmutableContext api = new ImmutableContext("u1", java.util.Map.of("app_contexts", new Value(java.util.List.of(new Value("api")))));
        assertEquals(ErrorCode.FLAG_NOT_FOUND, client.getBooleanDetails("web", false, api).getErrorCode());
        ImmutableContext web = new ImmutableContext("u1", java.util.Map.of("app_contexts", new Value(java.util.List.of(new Value("web")))));
        assertTrue(client.getBooleanValue("web", false, web));
    }

    @Test
    void payloads() {
        assertEquals(5, client.getIntegerValue("limit", 0, ctx()));
        assertEquals(1.5, client.getDoubleValue("ratio", 0.0, ctx()));
        Value v = client.getObjectValue("color", new Value(), ctx());
        assertEquals("#00f", v.asStructure().getValue("hex").asString());
    }

    @Test
    void errors() {
        FlagEvaluationDetails<Boolean> missing = client.getBooleanDetails("nope", true, ctx());
        assertTrue(missing.getValue());
        assertEquals(ErrorCode.FLAG_NOT_FOUND, missing.getErrorCode());

        FlagEvaluationDetails<Boolean> noKey = client.getBooleanDetails("on", false, new ImmutableContext());
        assertFalse(noKey.getValue());
        assertEquals(ErrorCode.TARGETING_KEY_MISSING, noKey.getErrorCode());

        FlagEvaluationDetails<String> mismatch = client.getStringDetails("on", "d", ctx());
        assertEquals("d", mismatch.getValue());
        assertEquals(ErrorCode.TYPE_MISMATCH, mismatch.getErrorCode());
        assertEquals(ErrorCode.TYPE_MISMATCH, client.getIntegerDetails("ratio", 7, ctx()).getErrorCode());
    }

    @Test
    void reasonTableMatchesSharedJson() throws Exception {
        JsonNode table = new ObjectMapper().readTree(Files.readString(Path.of("../../testdata/openfeature/reasons.json"))).get("reasons");
        for (Iterator<Map.Entry<String, JsonNode>> it = table.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            assertEquals(e.getValue().get("reason").asText(), LucidaProvider.reason(new FlagResult(Boolean.TRUE, e.getKey(), -1, null, null)).toString(), e.getKey());
            if (e.getValue().has("variant_reason")) {
                assertEquals(e.getValue().get("variant_reason").asText(), LucidaProvider.reason(new FlagResult("v", e.getKey(), 0, "v", null)).toString());
            }
        }
        assertEquals("UNKNOWN", LucidaProvider.reason(new FlagResult(Boolean.TRUE, "brand_new", -1, null, null)).toString());
    }

    @Test
    void metadataAndSplit() {
        FlagEvaluationDetails<String> color = client.getStringDetails("color", "none", ctx());
        assertEquals("SPLIT", color.getReason());
        assertEquals("condition_match", color.getFlagMetadata().getString("lucidaReason"));
        assertEquals(0, color.getFlagMetadata().getInteger("conditionIndex"));
        assertTrue(color.getFlagMetadata().getBoolean("evaluatedLocally"));
    }

    @Test
    void fallbackKeepsValueWithoutError() {
        FlagEvaluationDetails<Boolean> d = client.getBooleanDetails("dark", false, ctx());
        assertTrue(d.getValue());
        assertEquals("DEFAULT", d.getReason());
        assertEquals(null, d.getErrorCode());
        assertEquals("fallback", d.getFlagMetadata().getString("lucidaReason"));
    }

    @Test
    void groupsFeedLucidaGroups() {
        ImmutableContext c = new ImmutableContext("u1", Map.of("groups", new Value(new ImmutableStructure(Map.of("org", new Value("acme"))))));
        FlagContext fc = LucidaProvider.toFlagContext("u1", c);
        assertEquals(Map.of("org", "acme"), fc.groups());
    }

    @Test
    void trackCapturesAnEvent() {
        int before = lucida.captured().size();
        provider.track("purchase", ctx(), new dev.openfeature.sdk.MutableTrackingEventDetails(9.5).add("sku", "a"));
        var e = lucida.captured().get(before);
        assertEquals("purchase", e.get("event").asText());
        assertEquals(9.5, e.get("properties").get("$value").asDouble());
        assertEquals("a", e.get("properties").get("sku").asText());
    }

    @Test
    void emitsConfigurationChangedAndStale() throws Exception {
        CountDownLatch changed = new CountDownLatch(1), stale = new CountDownLatch(1);
        client.onProviderConfigurationChanged(d -> changed.countDown());
        client.onProviderStale(d -> stale.countDown());
        provider.changed();
        provider.stale();
        assertTrue(changed.await(5, TimeUnit.SECONDS));
        assertTrue(stale.await(5, TimeUnit.SECONDS));
    }
}
