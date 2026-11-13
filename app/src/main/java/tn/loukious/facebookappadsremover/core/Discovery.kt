package tn.loukious.facebookappadsremover.core

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * A named hook-point target: a DexKit query anchored on stable string
 * constants.
 *
 * Facebook obfuscates every class name per release, so hooks cannot reference
 * classes directly. Each feature registers [HookTarget]s whose queries are
 * anchored on constants that survive obfuscation (JSON keys, log literals,
 * enum names). This mirrors how the original mod located its hook
 * points at runtime (it used dexplore; we use DexKit).
 */
data class HookTarget(
    /** Stable key used in logs and reports, e.g. "account.cookieFilter". */
    val key: String,
    /** Human-readable description for the debug report. */
    val description: String,
    /**
     * Hook every candidate instead of requiring a unique match. The original
     * mod hooked ALL overloads of a resolved method and several near-identical
     * ad-pipeline classes at once; multi-targets express that.
     */
    val multi: Boolean = false,
    /** What the hook does once installed (consumed by the hook layer). */
    val action: HookAction? = null,
    /** DexKit query returning matched raw dex methods (may be empty). */
    val query: (DexKitBridge) -> List<MethodData>,
)

/** Blocking behavior of an installed hook (mirrors the mod's callback types). */
enum class HookAction {
    /** setResult(null) — kill fetch/render/vend paths (mod: bsrncODWYJ5TNENFG95a / FNjBx). */
    BLOCK_NULL,
    /** return false — boolean ad checks (mod: banner-ads replacement). */
    BLOCK_FALSE,
    /**
     * Ad-check the receiver/args first; null only sponsored stories
     * (mod: FNjBxTKppvYVTRFkExAd field-read + X.2Wa.A00 classifier).
     */
    SPONSORED_NULL,
    /**
     * Null only when the receiver's own toString contains "SPONSORED"
     * (mod: UVFD3BoJI5sq4SwfZZu0 on X.4qr.A00 — String.valueOf(thisObject)
     * .contains("SPONSORED") over the FBShortsMidCardFeedUnit TreeJNI dump).
     */
    RECEIVER_SPONSORED_NULL,
}

/**
 * Runtime hook-point discovery. Finds methods by string anchors via DexKit,
 * logs FOUND / NOT_UNIQUE / NOT_FOUND per target (same scheme as the original
 * mod), and resolves matched methods against the app classloader.
 */
class Discovery {

    companion object {
        private const val TAG = "FBAR.Discovery"
    }

    /** Result of one target's discovery, for reporting and hooking. */
    data class Report(
        val key: String,
        val status: Status,
        val detail: String,
        val methods: List<Method> = emptyList(),
    )

    enum class Status { FOUND, NOT_UNIQUE, NOT_FOUND, QUERY_FAILED }

    fun discover(bridge: DexKitBridge, classLoader: ClassLoader, targets: List<HookTarget>): List<Report> =
        targets.map { target ->
            try {
                val hits = target.query(bridge)
                when {
                    hits.isEmpty() -> {
                        L.w(TAG, "NOT_FOUND key=${target.key}")
                        Report(target.key, Status.NOT_FOUND, target.description)
                    }
                    hits.size > 1 && !target.multi -> {
                        L.w(TAG, "NOT_UNIQUE key=${target.key} candidates=${hits.size}: " +
                                hits.take(5).joinToString { it.descriptor })
                        Report(target.key, Status.NOT_UNIQUE, "${target.description} (${hits.size} candidates)")
                    }
                    else -> resolve(target, hits, classLoader)
                }
            } catch (t: Throwable) {
                L.w(TAG, "QUERY_FAILED key=${target.key}", t)
                Report(target.key, Status.QUERY_FAILED, target.description)
            }
        }

    private fun resolve(target: HookTarget, hits: List<MethodData>, classLoader: ClassLoader): Report {
        val methods = mutableListOf<Method>()
        val clinitOnly = StringBuilder()
        for (hit in hits) {
            // <clinit> cannot be represented as a java.lang.reflect.Method; the
            // class identification itself is still a successful discovery.
            if (hit.name == "<clinit>") {
                clinitOnly.append(hit.declaredClassName).append(' ')
                continue
            }
            val method = runCatching { hit.getMethodInstance(classLoader) }
                .onFailure {
                    // The cause matters: a ClassNotFound here means the scanned
                    // dex source belongs to a different app build than the
                    // running classloader, not that the anchor disappeared.
                    L.w(TAG, "RESOLVE_FAILED key=${target.key} ${hit.descriptor}: $it")
                }
                .getOrNull()
            if (method != null) {
                methods.add(method)
            }
        }
        return if (methods.isEmpty() && clinitOnly.isEmpty()) {
            L.w(TAG, "NOT_FOUND key=${target.key} (no resolvable methods)")
            Report(target.key, Status.NOT_FOUND, target.description)
        } else {
            for (m in methods) L.i(TAG, "FOUND key=${target.key} target=${m.declaringClass.name}.${m.name}/${m.parameterCount}")
            Report(target.key, Status.FOUND, "${target.description}: ${methods.size} method(s) ${clinitOnly}".trim(), methods)
        }
    }
}
