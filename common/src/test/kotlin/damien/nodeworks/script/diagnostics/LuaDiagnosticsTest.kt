package damien.nodeworks.script.diagnostics

import damien.nodeworks.block.entity.ProcessingStorageBlockEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Test

class LuaDiagnosticsTest {

    private class IdCase(
        val name: String,
        val script: String,
        val expectedUnknowns: List<String>,
        val symbols: Map<String, String> = emptyMap(),
    )

    @TestFactory
    fun unknownIdentifierFlagsTypos(): List<DynamicTest> {
        val cases = listOf(
            IdCase("clean script", "local x = 1\nprint(x)", emptyList()),
            IdCase("misspelled global", "netwrok:get('a')", listOf("netwrok")),
            IdCase("misspelled bare function", "prit('hi')", listOf("prit")),
            IdCase("undeclared local", "print(notDefined)", listOf("notDefined")),
            IdCase(
                "local declared then used is fine",
                "local x = 1\nprint(x)",
                emptyList(),
            ),
            IdCase(
                "function declaration adds the name",
                "function helper() end\nhelper()",
                emptyList(),
            ),
            IdCase(
                "function param is in scope",
                "function f(a) print(a) end",
                emptyList(),
            ),
            IdCase(
                "for-loop binding is in scope",
                "local xs = {1,2,3}\nfor _, v in xs do print(v) end",
                emptyList(),
            ),
            IdCase(
                "numeric for-loop binding is in scope",
                "for i=1, 5 do print(i) end",
                emptyList(),
            ),
            IdCase(
                "numeric for-loop with spaces around equals is in scope",
                "for i = 1, 5 do print(i) end",
                emptyList(),
            ),
            IdCase(
                "numeric for-loop with step is in scope",
                "for i=1, 10, 2 do print(i) end",
                emptyList(),
            ),
            IdCase(
                "nested unknown still flagged",
                "function f(a) print(typo) end",
                listOf("typo"),
            ),
            IdCase(
                "multiple unknowns flagged separately",
                "alpha = bet + gam",
                listOf("alpha", "bet", "gam"),
            ),
            IdCase(
                "module global recognised",
                "network:debug()",
                emptyList(),
            ),
            IdCase(
                "stdlib module recognised",
                "print(string.format('%d', 5))",
                emptyList(),
            ),
            IdCase(
                "type annotation is not a reference",
                "local items: ItemsHandle = network:find('coal')",
                emptyList(),
            ),
            IdCase(
                "function param annotation is not a reference",
                "function handle(card: CardHandle) print(card.name) end",
                emptyList(),
                symbols = mapOf("card" to "CardHandle"),
            ),
            IdCase(
                "comments are ignored",
                "-- prit is a typo, but inside a comment\nprint('ok')",
                emptyList(),
            ),
            IdCase(
                "string contents are ignored",
                "print('netwrok is fine inside a string')",
                emptyList(),
            ),
            IdCase(
                "keyword typo surfaces as unknown identifier",
                "funtcion f() end",
                // 'funtcion' is the typo; 'f' is then an undeclared name because
                // 'function' wasn't actually declared. Both flagged.
                listOf("funtcion", "f"),
            ),
        )

        return cases.map { c ->
            dynamicTest(c.name) {
                val diags = LuaDiagnostics.analyze(c.script, c.symbols)
                val unknowns = diags
                    .filter { it.code == "unknown-identifier" }
                    .map { c.script.substring(it.range.start, it.range.end) }
                assertEquals(c.expectedUnknowns, unknowns, "got: $diags")
            }
        }
    }

    @Test
    fun severityComesFromTheRuleTable() {
        val diags = LuaDiagnostics.analyze("missingName")
        val diag = diags.firstOrNull()
        assertNotNull(diag, "expected an unknown-identifier diagnostic")
        assertEquals(Severity.ERROR, diag!!.severity, "default severity is ERROR")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Member access (unknown-method, unknown-property)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun unknownMethodOnTypedReceiverFlags() {
        val script = "card:fnid()"
        val diags = LuaDiagnostics.analyze(script, mapOf("card" to "CardHandle"))
        val methodDiag = diags.firstOrNull { it.code == "unknown-method" }
        assertNotNull(methodDiag, "expected unknown-method diagnostic, got $diags")
        assertEquals("fnid", script.substring(methodDiag!!.range.start, methodDiag.range.end))
    }

    @Test
    fun knownMethodOnTypedReceiverIsClean() {
        val diags = LuaDiagnostics.analyze("card:find('coal')", mapOf("card" to "CardHandle"))
        assertTrue(
            diags.none { it.code == "unknown-method" || it.code == "unknown-property" },
            "expected no member diagnostics, got $diags",
        )
    }

    @Test
    fun unknownPropertyOnTypedReceiverFlags() {
        val script = "items.cuont"
        val diags = LuaDiagnostics.analyze(script, mapOf("items" to "ItemsHandle"))
        val diag = diags.firstOrNull { it.code == "unknown-property" }
        assertNotNull(diag)
        assertEquals("cuont", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableTypeStillResolvesForMembers() {
        // ItemsHandle? should still know its methods (we strip the ? before lookup)
        val diags = LuaDiagnostics.analyze("items.count", mapOf("items" to "ItemsHandle?"))
        assertTrue(
            diags.none { it.code == "unknown-method" || it.code == "unknown-property" },
            "got $diags",
        )
    }

    @Test
    fun unknownReceiverDoesNotFlagMember() {
        // We don't know what type 'whatever' is; skip the member check rather
        // than blame the member.
        val script = "whatever:method()"
        val diags = LuaDiagnostics.analyze(script)
        // 'whatever' is unknown-identifier (the bare receiver), but 'method'
        // is NOT flagged because we have no type info to validate it against.
        assertTrue(
            diags.none { it.code == "unknown-method" },
            "method should not be flagged when receiver type is unknown, got $diags",
        )
        assertTrue(
            diags.any { it.code == "unknown-identifier" },
            "receiver should be flagged as unknown identifier",
        )
    }

    @Test
    fun moduleMethodKnownAreClean() {
        val diags = LuaDiagnostics.analyze("network:debug()")
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun stdlibMethodKnownAreClean() {
        val diags = LuaDiagnostics.analyze("print(math.max(1, 2))")
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun stdlibMethodTypoFlags() {
        val script = "print(math.maxx(1, 2))"
        val diags = LuaDiagnostics.analyze(script)
        val diag = diags.firstOrNull { it.code == "unknown-method" }
        assertNotNull(diag)
        assertEquals("maxx", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun emptyScriptProducesNoDiagnostics() {
        assertEquals(emptyList<Diagnostic>(), LuaDiagnostics.analyze(""))
    }

    // ──────────────────────────────────────────────────────────────────────
    // ambiguous-card-name: `network:get("name")` on a duplicated literal
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun ambiguousNetworkGetFlagsAsHint() {
        val script = "network:get('cobblestone')"
        val diag = LuaDiagnostics.analyze(
            script,
            ambiguousNetworkNames = setOf("cobblestone"),
        ).firstOrNull { it.code == "ambiguous-card-name" }
        assertNotNull(diag, "expected ambiguous-card-name HINT")
        assertEquals(Severity.HINT, diag!!.severity)
        assertEquals("cobblestone", script.substring(diag.range.start, diag.range.end))
    }

    @Test
    fun ambiguousFlagAlsoFiresOnChannelGet() {
        // `Channel:get` resolves through the same bare-name namespace as
        // `Network:get`, so the rule fires there too.
        val script = "network:channel('white'):get('cobblestone')"
        val diags = LuaDiagnostics.analyze(
            script,
            ambiguousNetworkNames = setOf("cobblestone"),
        ).filter { it.code == "ambiguous-card-name" }
        assertEquals(1, diags.size)
    }

    @Test
    fun nonAmbiguousNameIsClean() {
        val script = "network:get('cobblestone')"
        val diags = LuaDiagnostics.analyze(
            script,
            ambiguousNetworkNames = setOf("dirt"),
        ).filter { it.code == "ambiguous-card-name" }
        assertTrue(diags.isEmpty())
    }

    @Test
    fun ambiguousFlagNotFiredOnFindEachOrGetAll() {
        // `:findEach` / `:getAll` are collection lookups, they take a filter or
        // type, not a literal alias, so even a script that happens to pass an
        // ambiguous name as their string arg shouldn't trigger this rule.
        val script = """
            network:findEach('cobblestone')
            network:getAll('cobblestone')
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(
            script,
            ambiguousNetworkNames = setOf("cobblestone"),
        ).filter { it.code == "ambiguous-card-name" }
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun ambiguousFlagSkipsCommentedCalls() {
        val script = "-- network:get('cobblestone')"
        val diags = LuaDiagnostics.analyze(
            script,
            ambiguousNetworkNames = setOf("cobblestone"),
        ).filter { it.code == "ambiguous-card-name" }
        assertTrue(diags.isEmpty())
    }

    @Test
    fun ambiguousFlagFiresOnRouteWithGlobHint() {
        val script = "network:route('cobblestone', function(items) end)"
        val diag = LuaDiagnostics.analyze(
            script,
            ambiguousNetworkNames = setOf("cobblestone"),
        ).firstOrNull { it.code == "ambiguous-card-name" }
        assertNotNull(diag, "expected hint on :route(ambiguous)")
        assertEquals("cobblestone", script.substring(diag!!.range.start, diag.range.end))
        assertTrue(diag.message.contains("cobblestone_*"), "should suggest glob, got '${diag.message}'")
    }

    @Test
    fun ambiguousFlagFiresOnImporterFromAndTo() {
        val script = "importer:from('cobblestone'):to('iron')"
        val diags = LuaDiagnostics.analyze(
            script,
            ambiguousNetworkNames = setOf("cobblestone", "iron"),
        ).filter { it.code == "ambiguous-card-name" }
        assertEquals(2, diags.size, "expected hints on both :from and :to, got $diags")
    }

    @Test
    fun ambiguousFlagFiresOnStockerFromAndTo() {
        val script = "stocker:from('cobblestone'):to('iron')"
        val diags = LuaDiagnostics.analyze(
            script,
            ambiguousNetworkNames = setOf("cobblestone", "iron"),
        ).filter { it.code == "ambiguous-card-name" }
        assertEquals(2, diags.size)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public nullablesAtOffset helper (used by hover tooltip / autocomplete)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun nullablesAtOffsetReportsNullableOutsideNarrowingBlock() {
        val script = """
            local all: ItemsHandle? = network:find('coal')
            print(all)
        """.trimIndent()
        val printPos = script.indexOf("print")
        val nullables = LuaDiagnostics.nullablesAtOffset(script, printPos)
        assertTrue("all" in nullables, "expected `all` nullable at print, got $nullables")
    }

    @Test
    fun nullablesAtOffsetDropsNullableInsideIfBlock() {
        val script = """
            local all: ItemsHandle? = network:find('coal')
            if all then
                print(all)
            end
        """.trimIndent()
        val insidePos = script.indexOf("print(all)")
        val nullables = LuaDiagnostics.nullablesAtOffset(script, insidePos)
        assertTrue("all" !in nullables, "expected `all` narrowed at insidePos, got $nullables")
    }

    @Test
    fun nullablesAtOffsetDropsNullableAfterEarlyReturnGuard() {
        val script = """
            local all: ItemsHandle? = network:find('coal')
            if not all then return end
            print(all)
        """.trimIndent()
        val printPos = script.indexOf("print")
        val nullables = LuaDiagnostics.nullablesAtOffset(script, printPos)
        assertTrue("all" !in nullables, "expected `all` narrowed after early-return, got $nullables")
    }

    @Test
    fun nullablesAtOffsetUsesSymbolTableForChainInference() {
        // io_1 is CardHandle (passed in via symbols), io_1:find returns ItemsHandle?,
        // so `all` should be inferred nullable.
        val script = """
            local all = io_1:find('*')
            print(all)
        """.trimIndent()
        val printPos = script.indexOf("print")
        val nullables = LuaDiagnostics.nullablesAtOffset(script, printPos, mapOf("io_1" to "CardHandle"))
        assertTrue("all" in nullables, "expected `all` inferred nullable, got $nullables")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Nullable misuse (nullable-misuse)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun nullableExplicitAnnotationFlagsBareAccess() {
        val script = """
            local items: ItemsHandle? = network:find('coal')
            items:matches('iron')
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-misuse" }
        assertNotNull(diag, "expected nullable-misuse")
        assertEquals("items", script.substring(diag!!.range.start, diag.range.end))
        assertEquals(Severity.WARNING, diag.severity)
    }

    @Test
    fun nullablePropertyAccessFlags() {
        val script = "local items: ItemsHandle? = network:find('coal')\nprint(items.count)"
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-misuse" }
        assertNotNull(diag, "expected nullable-misuse")
        assertEquals("items", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableInferredFromRegistryFindFlags() {
        // No explicit annotation, but `network:find` returns ItemsHandle?, so the
        // analyzer should still flag the unguarded access.
        val script = "local items = network:find('coal')\nitems:matches('iron')"
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-misuse" }
        assertNotNull(diag, "expected nullable-misuse, got ${LuaDiagnostics.analyze(script)}")
        assertEquals("items", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableInsideIfThenIsClean() {
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if items then
                items:matches('iron')
            end
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "expected no nullable-misuse inside `if items then`, got $diags",
        )
    }

    @Test
    fun nullableInsideExplicitNilCheckIsClean() {
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if items ~= nil then
                items:matches('iron')
            end
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "expected no nullable-misuse inside `if items ~= nil then`, got $diags",
        )
    }

    @Test
    fun nullableAfterAssertIsClean() {
        val script = """
            local items: ItemsHandle? = network:find('coal')
            assert(items)
            items:matches('iron')
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "expected no nullable-misuse after assert, got $diags",
        )
    }

    @Test
    fun nullableAccessOutsideIfBlockStillFlags() {
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if items then
                items:matches('iron')
            end
            items.count
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script).filter { it.code == "nullable-misuse" }
        assertEquals(1, diags.size, "expected one flag on the post-block access, got $diags")
        // The flagged occurrence is the `items` after the closing `end`.
        val diag = diags.single()
        val flagged = script.substring(diag.range.start, diag.range.end)
        assertEquals("items", flagged)
        // Sanity: the offset must be after the closing `end`.
        assertTrue(diag.range.start > script.indexOf("end"))
    }

    @Test
    fun nullableNestedBlocksTrackDepthCorrectly() {
        // The nested `if condition then end` shouldn't be misread as the closer of
        // the outer `if items then`, the analyzer tracks block depth via keywords.
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if items then
                if true then
                    items:matches('iron')
                end
            end
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "expected nested narrowing block to still narrow, got $diags",
        )
    }

    @Test
    fun nonNullableLocalIsNotFlagged() {
        val script = """
            local items: ItemsHandle = network:find('coal')
            items:matches('iron')
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "non-nullable annotation should not be flagged, got $diags",
        )
    }

    @Test
    fun nullableFunctionParamFlagged() {
        val script = """
            function handle(card: CardHandle?)
                card:find('coal')
            end
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-misuse" }
        assertNotNull(diag, "expected nullable-misuse on nullable param access")
        assertEquals("card", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableInElseifBranchIsClean() {
        // `elseif fromInput then` narrows `fromInput` inside that branch the same
        // way `if fromInput then` would.
        val script = """
            local fromBuf: ItemsHandle? = network:find('coal')
            local fromInput: ItemsHandle? = network:find('iron')
            if fromBuf then
                io_1:insert(fromBuf)
            elseif fromInput then
                io_1:insert(fromInput)
            end
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script, mapOf("io_1" to "CardHandle"))
            .filter { it.code == "nullable-arg" || it.code == "nullable-misuse" }
        assertTrue(diags.isEmpty(), "elseif branch should narrow, got $diags")
    }

    @Test
    fun nullableInElseBranchStillFlags() {
        // The `else` branch runs when items is FALSY (nil), so dereferencing
        // there must still be flagged. Pre-fix the analyzer's narrowing region
        // ran from `then` straight to `end`, swallowing the else body too.
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if items then
                items:matches('a')
            else
                items:matches('b')
            end
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script).filter { it.code == "nullable-misuse" }
        assertEquals(1, diags.size, "expected one flag in else branch, got $diags")
        // The flagged occurrence is the `items` after `else`.
        val diag = diags.single()
        assertTrue(diag.range.start > script.indexOf("else"))
    }

    @Test
    fun nullableAfterEarlyReturnGuardIsClean() {
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if not items then return end
            items:matches('iron')
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "expected `if not items then return end` to narrow, got $diags",
        )
    }

    @Test
    fun nullableAfterExplicitNilEqualsGuardIsClean() {
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if items == nil then return end
            items:matches('iron')
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "expected `if items == nil then return end` to narrow, got $diags",
        )
    }

    @Test
    fun nullableAfterReversedNilEqualsGuardIsClean() {
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if nil == items then return end
            items:matches('iron')
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "expected `if nil == items then return end` to narrow, got $diags",
        )
    }

    @Test
    fun nullableAfterErrorGuardIsClean() {
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if not items then error('no items') end
            items:matches('iron')
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "expected `if not items then error(...) end` to narrow, got $diags",
        )
    }

    @Test
    fun nullableAfterMultilineEarlyReturnGuardIsClean() {
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if not items then
                print('no coal')
                return
            end
            items:matches('iron')
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "expected multi-line guard with print + return to narrow, got $diags",
        )
    }

    @Test
    fun nullableAfterNonTerminatingGuardStillFlagged() {
        // Body has no return/break/error, so falling through leaves items still possibly
        // nil, the access after the `end` must still be flagged.
        val script = """
            local items: ItemsHandle? = network:find('coal')
            if not items then print('warning') end
            items:matches('iron')
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-misuse" }
        assertNotNull(diag, "expected nullable-misuse, body doesn't terminate")
        // The flagged occurrence is the `items` after the `end`.
        assertTrue(diag!!.range.start > script.indexOf("end"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // nullable-arg: passing a `T?` to a non-nullable parameter
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun nullableArgFlaggedOnNonNullableParam() {
        val script = """
            local io_1 = network:get('io_1')
            network:craft('minecraft:oak_door'):connect(function(items: ItemsHandle?)
                io_1:insert(items)
            end)
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(script, mapOf("io_1" to "CardHandle"))
            .firstOrNull { it.code == "nullable-arg" }
        assertNotNull(diag, "expected nullable-arg on `io_1:insert(items)`")
        assertEquals("items", script.substring(diag!!.range.start, diag.range.end))
        assertEquals(Severity.WARNING, diag.severity)
    }

    @Test
    fun nullableArgCleanWhenNarrowedFirst() {
        val script = """
            local io_1 = network:get('io_1')
            network:craft('minecraft:oak_door'):connect(function(items: ItemsHandle?)
                if items then
                    io_1:insert(items)
                end
            end)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script, mapOf("io_1" to "CardHandle"))
        assertTrue(
            diags.none { it.code == "nullable-arg" },
            "narrowed inside `if items then` should not be flagged, got $diags",
        )
    }

    @Test
    fun nullableArgCleanWhenParamItselfIsNullable() {
        // `network:onInsert` (callback wrapper) declares no inputs that are non-null in
        // a way we'd flag. Use a synthetic case: pretend a method takes `T?`. We mimic
        // by checking that passing a nullable as the second arg of `:cas` (which is
        // typed `Any`) does NOT warn, because Any tolerates nil at the spec level.
        val script = """
            local v: NumberVariableHandle? = network:get('counter')
            v:cas(1, 2)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script).filter { it.code == "nullable-arg" }
        // The receiver `v` is nullable, that's `nullable-misuse` (member access), not
        // `nullable-arg` (the args themselves are number literals, both non-null).
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun nullableArgCleanWhenReceiverUnknown() {
        // No symbols entry for `unknown` and it's not a module global, so we can't
        // resolve the param spec. Skip silently rather than guess.
        val script = """
            local items: ItemsHandle? = network:find('coal')
            unknown:insert(items)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script).filter { it.code == "nullable-arg" }
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun nullableInferredFromTypedLocalReceiver() {
        // `io_1` is CardHandle (from network:get), and CardHandle:find returns
        // ItemsHandle?. The chain resolver should detect that `all` is nullable
        // even though the receiver is a local rather than a module global.
        val script = """
            local all = io_1:find('*')
            storage_1:insert(all)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(
            script,
            mapOf("io_1" to "CardHandle", "storage_1" to "CardHandle"),
        )
        // `storage_1:insert(all)` should now flag `all` as nullable-arg.
        val argDiag = diags.firstOrNull { it.code == "nullable-arg" }
        assertNotNull(argDiag, "expected nullable-arg, got $diags")
        assertEquals("all", script.substring(argDiag!!.range.start, argDiag.range.end))
    }

    @Test
    fun nullableInferredFromMultilineChainRhs() {
        // The RHS spans multiple lines, the chain resolver should still walk it.
        val script = """
            local first = network
                :channel('white')
                :getFirst('io')
            first:find('coal')
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-misuse" }
        assertNotNull(diag, "expected `first` to be inferred nullable")
        assertEquals("first", script.substring(diag!!.range.start, diag.range.end))
    }

    // ──────────────────────────────────────────────────────────────────────
    // Chain access on nullable call result
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun chainAccessOnNullableCallResultFlags() {
        // network:channel("white") → Channel (non-null)
        // Channel:getFirst("io") → CardHandle?
        // :face("top") on a CardHandle? receiver, the analyzer should flag `face`.
        val script = "network:channel('white'):getFirst('io'):face('top')"
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-misuse" }
        assertNotNull(diag, "expected nullable-misuse on chained access, got ${LuaDiagnostics.analyze(script)}")
        assertEquals("face", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun chainAccessOnNonNullableResultIsClean() {
        // network:channel(...) returns Channel (non-null), so chaining `:get` is fine.
        val script = "network:channel('white'):get('io_1')"
        val diags = LuaDiagnostics.analyze(script).filter { it.code == "nullable-misuse" }
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun chainAccessOnPropertyOfNullableFlags() {
        // Reading `.name` on the result of getFirst should flag too, same issue,
        // different separator.
        val script = "network:channel('white'):getFirst('io').name"
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-misuse" }
        assertNotNull(diag, "expected `name` flagged for chain-on-nullable property")
        assertEquals("name", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun captureAndNarrowChainResultIsClean() {
        // Capturing the chain result lets the user narrow it normally.
        val script = """
            local first = network:channel('white'):getFirst('io')
            if first then
                first:face('top')
            end
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script).filter { it.code == "nullable-misuse" }
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun functionTableMethodParamIsInScope() {
        // `function foo.bar(a: ItemsHandle)`, the param `a` should be recognised
        // as declared inside the function body. Prior to the fix it was flagged
        // as an unknown identifier because the param regex stopped at the `.`.
        val script = """
            local foo = {}
            function foo.bar(a: ItemsHandle)
                print(a)
            end
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script).filter { it.code == "unknown-identifier" }
        assertTrue(
            diags.none { script.substring(it.range.start, it.range.end) == "a" },
            "expected `a` to be in scope, got $diags",
        )
    }

    @Test
    fun nullableArgFlaggedOnTableMethodCall() {
        // Same-script `function foo.bar(a: ItemsHandle)` declared, then called
        // with a nullable. The bare-name `foo.bar(...)` call goes through the
        // `.` separator path of checkNullableArgs.
        val script = """
            local foo = {}
            function foo.bar(a: ItemsHandle)
            end
            local items: ItemsHandle? = network:find('coal')
            foo.bar(items)
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-arg" }
        assertNotNull(diag, "expected nullable-arg on `foo.bar(items)`, got ${LuaDiagnostics.analyze(script)}")
        assertEquals("items", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableArgFlaggedOnImportedTableMethod() {
        // Cross-script: foo.lua declares `function foo.bar(a: ItemsHandle)` and
        // `return foo`. main.lua does `local foo = require("foo")` and calls
        // `foo.bar(nullable)`. The analyzer should resolve through the require
        // and flag `items`.
        val fooScript = """
            local foo = {}
            function foo.bar(a: ItemsHandle)
            end
            return foo
        """.trimIndent()
        val mainScript = """
            local foo = require("foo")
            local items: ItemsHandle? = network:find('coal')
            foo.bar(items)
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(mainScript, otherScripts = mapOf("foo" to fooScript))
            .firstOrNull { it.code == "nullable-arg" }
        assertNotNull(
            diag,
            "expected nullable-arg, got ${LuaDiagnostics.analyze(mainScript, otherScripts = mapOf("foo" to fooScript))}",
        )
        assertEquals("items", mainScript.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableArgImportedTableMethodWithDifferentLocalNameStillResolves() {
        // The local name in main.lua doesn't have to match the module's name.
        // `local m = require("foo")`, calls go through `m.bar`, which we re-key
        // from the module's `function foo.bar` declaration.
        val fooScript = """
            local foo = {}
            function foo.bar(a: ItemsHandle)
            end
            return foo
        """.trimIndent()
        val mainScript = """
            local m = require("foo")
            local items: ItemsHandle? = network:find('coal')
            m.bar(items)
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(mainScript, otherScripts = mapOf("foo" to fooScript))
            .firstOrNull { it.code == "nullable-arg" }
        assertNotNull(diag, "expected re-keying via local name, got ${LuaDiagnostics.analyze(mainScript, otherScripts = mapOf("foo" to fooScript))}")
        assertEquals("items", mainScript.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableArgImportedFromFullyCommentedModuleSkipsHarvest() {
        // The whole foo module is commented out, there's no real `foo.bar` to call.
        // The analyzer should harvest nothing from it and the call site `foo.bar(...)`
        // doesn't have a known param spec, so we don't flag (we'd skip silently for
        // unknown methods anyway). The point of the test is that we don't INVENT a
        // signature from commented code.
        val fooScript = """
            -- local foo = {}

            -- function foo.bar(a: ItemsHandle)
            --
            -- end

            -- return foo
        """.trimIndent()
        val mainScript = """
            local foo = require("foo")
            local items: ItemsHandle? = network:find('coal')
            foo.bar(items)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(mainScript, otherScripts = mapOf("foo" to fooScript))
            .filter { it.code == "nullable-arg" }
        assertTrue(
            diags.isEmpty(),
            "fully-commented module should not contribute a fake spec, got $diags",
        )
    }

    @Test
    fun localCommentedOutDeclarationDoesNotSilenceUnknown() {
        // The `local foo = 5` declaration is commented, so a downstream `print(foo)`
        // should still surface the unknown-identifier warning. Pre-fix the regex
        // happily picked the name out of the comment.
        val script = """
            -- local foo = 5
            print(foo)
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "unknown-identifier" }
        assertNotNull(diag, "expected `foo` flagged as unknown")
        assertEquals("foo", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableArgImportedTableMethodCleanWhenNarrowed() {
        val fooScript = """
            local foo = {}
            function foo.bar(a: ItemsHandle)
            end
            return foo
        """.trimIndent()
        val mainScript = """
            local foo = require("foo")
            local items: ItemsHandle? = network:find('coal')
            if items then
                foo.bar(items)
            end
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(mainScript, otherScripts = mapOf("foo" to fooScript))
            .filter { it.code == "nullable-arg" }
        assertTrue(diags.isEmpty(), "narrowed before call, no warning expected, got $diags")
    }

    @Test
    fun nullableArgFlaggedOnUserFunctionCall() {
        // The user-defined doThing(i: ItemsHandle) takes a non-nullable, calling it
        // with a nullable should warn even though doThing is a bare function.
        val script = """
            local function doThing(i: ItemsHandle)
            end

            network:craft('minecraft:oak_door')
                :connect(function(items: ItemsHandle?)
                    doThing(items)
                end)
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-arg" }
        assertNotNull(diag, "expected nullable-arg on `doThing(items)`")
        assertEquals("items", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableArgCleanForUserFunctionWithNullableParam() {
        // Same shape but the user function explicitly accepts a nullable.
        val script = """
            local function doThing(i: ItemsHandle?)
            end

            network:craft('minecraft:oak_door')
                :connect(function(items: ItemsHandle?)
                    doThing(items)
                end)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script).filter { it.code == "nullable-arg" }
        assertTrue(diags.isEmpty(), "user func declares param as `T?`, no warning, got $diags")
    }

    @Test
    fun nullableArgFlaggedOnChainedMethodCall() {
        // The chain `network:channel("red"):get("io_1"):insert(items)` resolves
        // through Network → Channel → CardHandle, and CardHandle:insert expects
        // a non-nullable ItemsHandle. The analyzer must walk the whole chain to
        // flag the trailing `items` arg.
        val script = """
            local items: ItemsHandle? = network:find('coal')
            network:channel('red'):get('io_1'):insert(items)
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(script).firstOrNull { it.code == "nullable-arg" }
        assertNotNull(diag, "expected chain receiver to resolve, got ${LuaDiagnostics.analyze(script)}")
        assertEquals("items", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun nullableArgCleanForUntypedFunctionParam() {
        // `function f(x)` with no annotation, un-typed params accept anything,
        // including nil, so we don't warn.
        val script = """
            local function maybeUse(x)
            end
            local items: ItemsHandle? = network:find('coal')
            maybeUse(items)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script).filter { it.code == "nullable-arg" }
        assertTrue(diags.isEmpty(), "untyped param should not warn, got $diags")
    }

    @Test
    fun nullableArgFlaggedOnMultiArgPositional() {
        // Insert is `:insert(items, count?)`, first arg is non-nullable ItemsHandle,
        // a bare nullable in arg-1 position should still flag with the second arg
        // present and unrelated.
        val script = """
            local io_1 = network:get('io_1')
            local items: ItemsHandle? = network:find('coal')
            io_1:insert(items, 32)
        """.trimIndent()
        val diag = LuaDiagnostics.analyze(script, mapOf("io_1" to "CardHandle"))
            .firstOrNull { it.code == "nullable-arg" }
        assertNotNull(diag)
        assertEquals("items", script.substring(diag!!.range.start, diag.range.end))
    }

    @Test
    fun bareNullableReferenceIsNotFlagged() {
        // Just referring to a nullable (passing it, comparing it) is fine, only
        // dereferencing it without a nil-check crashes.
        val script = """
            local items: ItemsHandle? = network:find('coal')
            print(items)
            if items == nil then return end
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.none { it.code == "nullable-misuse" },
            "bare references should not be flagged, got $diags",
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Handler-shape hints: handler-no-pull, handler-unused-input
    // ──────────────────────────────────────────────────────────────────────

    private fun api(
        name: String,
        inputs: List<Pair<String, Int>> = emptyList(),
        outputs: List<Pair<String, Int>> = emptyList(),
    ) = ProcessingStorageBlockEntity.ProcessingApiInfo(
        name = name, inputs = inputs, outputs = outputs, timeout = 0,
    )

    @Test
    fun handlerWithoutPullEmitsHint() {
        val script = """
            network:handle("smelt_iron", function(items, job)
                local x = items.copperOre
                -- never calls job:pull, the craft will hang
            end)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        val hint = requireNotNull(diags.singleOrNull { it.code == "handler-no-pull" }) {
            "expected handler-no-pull, got $diags"
        }
        assertEquals(Severity.HINT, hint.severity)
        assertTrue(hint.message.contains("smelt_iron"))
    }

    @Test
    fun handlerWithJobPullIsClean() {
        val script = """
            network:handle("smelt_iron", function(items, job)
                local out = job:pull(function(s) return s.item == "minecraft:iron_ingot" end)
            end)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(diags.none { it.code == "handler-no-pull" }, "got $diags")
    }

    @Test
    fun handlerWithCommentedOutPullStillFlagged() {
        // Comment-stripping should mean a `:pull(` hidden inside a comment
        // doesn't count toward the live :pull check.
        val script = """
            network:handle("smelt_iron", function(items, job)
                -- TODO: job:pull(function(s) return true end)
                items.copperOre.shapeless()
            end)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(
            diags.any { it.code == "handler-no-pull" },
            "expected hint despite commented :pull, got $diags",
        )
    }

    @Test
    fun nestedFunctionEndsDontProematurelyCloseHandlerBody() {
        // The inner `function … end` block must not be mistaken for the
        // handler's closing end. Here :pull lives *after* an inner block.
        val script = """
            network:handle("smelt_iron", function(items, job)
                local helper = function() return 1 end
                local out = job:pull(function(s) return s.item == "minecraft:iron_ingot" end)
            end)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(diags.none { it.code == "handler-no-pull" }, "got $diags")
    }

    @Test
    fun handlerNoPullSkippedForScriptsWithoutHandleCall() {
        // Cheap-out path: scripts that never call network:handle shouldn't
        // even run the handler-span scan.
        val script = """
            local cobble = network:get("cobblestone")
            cobble:set(true)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(script)
        assertTrue(diags.none { it.code == "handler-no-pull" }, "got $diags")
    }

    @Test
    fun handlerUnusedInputEmitsHint() {
        val script = """
            network:handle("alloy", function(items, job)
                local x = items.copperIngot
                job:pull(function(s) return s.item == "minecraft:steel_ingot" end)
                -- ironIngot is declared as an input but never referenced
            end)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(
            script,
            processingApis = listOf(api(
                name = "alloy",
                inputs = listOf(
                    "minecraft:copper_ingot" to 1,
                    "minecraft:iron_ingot" to 1,
                ),
            )),
        )
        val hint = requireNotNull(diags.singleOrNull { it.code == "handler-unused-input" }) {
            "expected handler-unused-input, got $diags"
        }
        assertEquals(Severity.HINT, hint.severity)
        assertTrue(hint.message.contains("ironIngot"), "message=${hint.message}")
        assertFalse(hint.message.contains("copperIngot"))
    }

    @Test
    fun handlerWithAllInputsReferencedIsClean() {
        val script = """
            network:handle("alloy", function(items, job)
                local a = items.copperIngot
                local b = items.ironIngot
                job:pull(function(s) return s.item == "minecraft:steel_ingot" end)
            end)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(
            script,
            processingApis = listOf(api(
                name = "alloy",
                inputs = listOf(
                    "minecraft:copper_ingot" to 1,
                    "minecraft:iron_ingot" to 1,
                ),
            )),
        )
        assertTrue(diags.none { it.code == "handler-unused-input" }, "got $diags")
    }

    @Test
    fun handlerUsingItemsBracketAccessSuppressesUnusedInputHint() {
        // Dynamic field access via `items["name"]` can't be statically verified,
        // so we don't fire the hint when the body uses bracket access at all.
        val script = """
            network:handle("alloy", function(items, job)
                for k, v in pairs({"copperIngot", "ironIngot"}) do
                    print(items[v])
                end
                job:pull(function(s) return s.item == "minecraft:steel_ingot" end)
            end)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(
            script,
            processingApis = listOf(api(
                name = "alloy",
                inputs = listOf(
                    "minecraft:copper_ingot" to 1,
                    "minecraft:iron_ingot" to 1,
                ),
            )),
        )
        assertTrue(diags.none { it.code == "handler-unused-input" }, "got $diags")
    }

    @Test
    fun handlerIteratingItemsSuppressesUnusedInputHint() {
        val script = """
            network:handle("alloy", function(items, job)
                for k, v in items do print(v) end
                job:pull(function(s) return s.item == "minecraft:steel_ingot" end)
            end)
        """.trimIndent()
        val diags = LuaDiagnostics.analyze(
            script,
            processingApis = listOf(api(
                name = "alloy",
                inputs = listOf("minecraft:copper_ingot" to 1, "minecraft:iron_ingot" to 1),
            )),
        )
        assertTrue(diags.none { it.code == "handler-unused-input" }, "got $diags")
    }
}
