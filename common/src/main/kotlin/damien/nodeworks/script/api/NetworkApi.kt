package damien.nodeworks.script.api

import damien.nodeworks.script.api.LuaType.Primitive.Any
import damien.nodeworks.script.api.LuaType.Primitive.Boolean
import damien.nodeworks.script.api.LuaType.Primitive.Number
import damien.nodeworks.script.api.LuaType.Primitive.String
import damien.nodeworks.script.api.LuaType.Primitive.Void

/**
 * Spec for the `network` Lua module, plus the related [Channel] and [HandleList]
 * types and the [CraftBuilder] returned by `network:craft`. These four surfaces
 * cross-reference each other so they live in one file rather than splitting by
 * type and duplicating the type declarations.
 *
 * Runtime impl in [damien.nodeworks.script.ScriptEngine.injectApi] for `network`,
 * plus the per-receiver builders constructed inside the network table.
 */

val Network: LuaType.Named = LuaTypes.module(
    global = "network",
    name = "Network",
    description = "The active network. Covers card lookup, storage queries, routing, and crafting.",
    guidebookRef = "nodeworks:lua-api/network.md",
)

val Channel: LuaType.Named = LuaTypes.type(
    name = "Channel",
    description = "A dye-color-scoped view of the network's cards and devices.",
    guidebookRef = "nodeworks:lua-api/channel.md",
)

val HandleList: LuaType.Named = LuaTypes.type(
    name = "HandleList",
    description = "A list of cards or devices that broadcasts write methods across every member. Use :list() for per-member access.",
    guidebookRef = "nodeworks:lua-api/handle-list.md",
)

val CraftBuilder: LuaType.Named = LuaTypes.type(
    name = "CraftBuilder",
    description = "Returned by `network:craft`. Configures how the craft result is delivered once it completes.",
    guidebookRef = "nodeworks:lua-api/network.md#craft",
)

val InputItems: LuaType.Named = LuaTypes.type(
    name = "InputItems",
    description = "Per-recipe bag of `ItemsHandle` fields, the second argument to a `network:handle` callback.",
    guidebookRef = "nodeworks:lua-api/network.md#handle",
)

val NetworkApi: ApiSurface = api(Network) {
    method("get") {
        param("name", NetworkName, description = "Name of a card, variable, breaker, or placer on this network.")
        returns(Any)
        description =
            "Returns the card, variable, breaker, or placer with this name. Errors if no match. Cards win on collision."
        guidebookRef = "nodeworks:lua-api/network.md#get"
    }

    method("getAll") {
        param("type", NetworkAccessorType, description = "Capability or device type to filter by.")
        returns(HandleList)
        description =
            "HandleList of every card or device matching this type. Broadcasts write methods across all members."
        guidebookRef = "nodeworks:lua-api/network.md#getAll"
    }

    method("cards") {
        param(
            "pattern",
            CardAliasPattern,
            description = "Alias or `<prefix>_*` glob pattern. `\"io_*\"` matches every card whose alias starts with `io_`.",
        )
        returns(HandleList)
        description =
            "Snapshot HandleList of every card whose alias matches `pattern`. Pair with `:face(name)` to override the access face on every member."
        guidebookRef = "nodeworks:lua-api/network.md#cards"
    }

    method("channel") {
        param("color", DyeColor, description = "Dye color identifying the channel.")
        returns(Channel)
        description = "Scopes lookups to a single dye-color channel. Errors on unknown color names."
        guidebookRef = "nodeworks:lua-api/network.md#channel"
    }

    method("channels") {
        returns(DyeColor.list())
        description = "Color names of every channel currently in use on the network."
        guidebookRef = "nodeworks:lua-api/network.md#channels"
    }

    method("find") {
        param("filter", Filter, description = "Resource filter, see Filter for syntax.")
        returns(ItemsHandle.optional())
        description =
            "Scans network storage for matching items or fluids. Returns an aggregated handle, or nil if nothing matches."
        guidebookRef = "nodeworks:lua-api/network.md#find"
    }

    method("findEach") {
        param("filter", Filter, description = "Resource filter, see Filter for syntax.")
        returns(ItemsHandle.list())
        description = "Returns a separate handle for every distinct resource matching the filter."
        guidebookRef = "nodeworks:lua-api/network.md#findEach"
    }

    method("count") {
        param("filter", Filter, description = "Resource filter.")
        returns(Number)
        description = "Total quantity in network storage matching the filter. Fluids count in mB."
        guidebookRef = "nodeworks:lua-api/network.md#count"
    }

    method("insert") {
        param("items", ItemsHandle, description = "Resource to move from its source into network storage.")
        param(
            "count",
            Number.optional(),
            description = "Optional count limit, defaults to the items handle's full count."
        )
        returns(Boolean)
        description =
            "Moves the full count from the handle's source into storage, or moves nothing. Returns true on success."
        guidebookRef = "nodeworks:lua-api/network.md#insert"
    }

    method("tryInsert") {
        param("items", ItemsHandle, description = "Resource to move from its source into network storage.")
        param("count", Number.optional(), description = "Optional count limit.")
        returns(Number)
        description = "Best-effort move into storage. Returns the count actually moved."
        guidebookRef = "nodeworks:lua-api/network.md#tryInsert"
    }

    method("craft") {
        param("itemId", Craftable, description = "Item the network can plan a recipe for.")
        param("count", Number.optional(), description = "Optional count, defaults to 1.")
        returns(CraftBuilder)
        description = "Queues a craft for the given item. Returns a CraftBuilder. The default behavior is to store the result into Network Storage, chain `:connect(fn)` to override."
        guidebookRef = "nodeworks:lua-api/network.md#craft"
    }

    method("route") {
        param(
            "alias",
            StorageCardAlias,
            description = "Storage card alias to route matching items to. Wildcard `<prefix>_*` matches every numbered card sharing the prefix."
        )
        param(
            "predicate",
            function {
                param("items", ItemsHandle)
                returns(Boolean)
            },
            description = "Predicate run on each insert, return true to route to [alias].",
        )
        returns(Void)
        description =
            "Routing rule. Items matching the predicate are directed to the card with this alias during `network:insert`."
        guidebookRef = "nodeworks:lua-api/network.md#route"
    }

    method("shapeless") {
        param("itemId", ItemId, description = "Output item from a vanilla shapeless recipe.")
        param("count", Number, description = "Number of crafts to attempt.")
        returns(ItemsHandle.optional())
        description = "Crafts via a vanilla shapeless recipe, pulling the given ingredients from storage."
        guidebookRef = "nodeworks:lua-api/network.md#shapeless"
    }

    method("handle") {
        param("cardName", CardAlias, description = "Card alias of the Processing Set to handle.")
        param(
            "handler",
            function {
                param("job", Job)
                param("inputs", InputItems)
                returns(Void)
            },
            description = "Body invoked once per craft. Emit outputs with `job:pull`, read input slots from `inputs.<slot>`.",
        )
        returns(Void)
        description =
            "Registers a processing handler for a Processing Set. The handler uses `job:pull` to emit each output."
        guidebookRef = "nodeworks:lua-api/network.md#handle"
    }

    method("debug") {
        returns(Void)
        description = "Prints a summary of the network to the terminal log."
        guidebookRef = "nodeworks:lua-api/network.md#debug"
    }
}

val ChannelApi: ApiSurface = api(Channel) {
    method("getFirst") {
        param("type", NetworkAccessorType, description = "Capability or device type to filter by.")
        returns(CardHandle.optional())
        description = "First card or device of this type on the channel, or nil if none match."
        guidebookRef = "nodeworks:lua-api/channel.md#getFirst"
    }

    method("getAll") {
        param("type", NetworkAccessorType.optional(), description = "Capability or device type. Omit for every member.")
        returns(HandleList)
        description = "HandleList of every card or device on this channel matching the type."
        guidebookRef = "nodeworks:lua-api/channel.md#getAll"
    }

    method("get") {
        param("alias", NetworkName, description = "Bare name of any card, variable, or device on this channel.")
        returns(CardHandle)
        description = "Alias lookup scoped to this channel. Errors if no match exists."
        guidebookRef = "nodeworks:lua-api/channel.md#get"
    }
}

val HandleListApi: ApiSurface = api(HandleList) {
    method("list") {
        returns(Any.list())
        description = "Returns the underlying array so scripts can iterate per-member or read individual values."
        guidebookRef = "nodeworks:lua-api/handle-list.md#list"
    }

    method("count") {
        returns(Number)
        description = "Number of members in the list."
        guidebookRef = "nodeworks:lua-api/handle-list.md#count"
    }

    method("face") {
        param("name", FaceName, description = "Face the override should target.")
        returns(HandleList)
        description =
            "Returns a new HandleList with each card member's access face overridden. Members that aren't cards (variables, breakers, placers) pass through unchanged."
        guidebookRef = "nodeworks:lua-api/handle-list.md#face"
    }
}

val CraftBuilderApi: ApiSurface = api(CraftBuilder) {
    callback("connect") {
        fn {
            param("items", ItemsHandle.optional())
            returns(Void)
        }
        returns(Void)
        description = "Callback fired when the craft resolves. Receives the output ItemsHandle on success, or `nil` if the craft failed (plan failed, async timed out)."
        guidebookRef = "nodeworks:lua-api/craft-builder.md#connect"
    }
}

/** [InputItems] has no statically-declared properties, its fields are recipe-derived
 *  at autocomplete time via the legacy [damien.nodeworks.screen.widget.AutocompletePopup]
 *  per-handler dispatch. The empty surface here is enough to register the TYPE for
 *  hover and type-annotation autocomplete, the dynamic-properties path falls
 *  through to legacy when [LuaApiRegistry.propertiesOf] returns empty. */
val InputItemsApi: ApiSurface = api(InputItems) {}
