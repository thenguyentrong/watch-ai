package com.vinhnguyen.watchai.buddy

import java.util.Locale

/** Where an item sits on Buddy; one item per slot at a time. */
public enum class Slot { HEAD, FACE, NECK, BODY, HAND }

/**
 * Clothes and accessories. Every Buddy can wear every item, whatever its shape: items are placed
 * on the outline's real radius. [price] is in coins, 0 for the free starter set; nothing is sold
 * yet, the prices are there so a shop can be added without changing saved outfits.
 */
public enum class Item(
    public val slot: Slot,
    public val label: String,
    public val price: Int,
) {
    CAP(Slot.HEAD, "cap", 0),
    BEANIE(Slot.HEAD, "beanie", 0),
    CHEF_HAT(Slot.HEAD, "chef's hat", 120),
    HARD_HAT(Slot.HEAD, "hard hat", 120),
    PARTY_HAT(Slot.HEAD, "party hat", 0),
    GRAD_CAP(Slot.HEAD, "graduation cap", 150),
    CROWN(Slot.HEAD, "crown", 400),
    HEADPHONES(Slot.HEAD, "headphones", 150),
    ROUND_GLASSES(Slot.FACE, "round glasses", 0),
    SUNGLASSES(Slot.FACE, "sunglasses", 100),
    BOW_TIE(Slot.NECK, "bow tie", 0),
    SCARF(Slot.NECK, "scarf", 80),
    NECKTIE(Slot.NECK, "necktie", 80),
    STETHOSCOPE(Slot.NECK, "stethoscope", 150),
    JERSEY(Slot.BODY, "basketball jersey", 150),
    LAB_COAT(Slot.BODY, "lab coat", 150),
    SAFETY_VEST(Slot.BODY, "safety vest", 120),
    APRON(Slot.BODY, "apron", 100),
    BASKETBALL(Slot.HAND, "basketball", 100),
    CONTROLLER(Slot.HAND, "game controller", 100),
    COFFEE(Slot.HAND, "coffee cup", 0),
    BOOK(Slot.HAND, "book", 0),
    ;

    public val wire: String get() = name.lowercase(Locale.ROOT)

    public companion object {
        public fun of(wire: String?): Item? = entries.firstOrNull { it.wire == wire }
    }
}

/** What Buddy wears: at most one item per slot. */
public class Outfit private constructor(
    public val items: Map<Slot, Item>,
) {
    public operator fun get(slot: Slot): Item? = items[slot]

    /** Wire form: item names, comma-separated, in slot order. */
    public val wire: String get() = Slot.entries.mapNotNull { items[it]?.wire }.joinToString(",")

    override fun equals(other: Any?): Boolean = other is Outfit && other.items == items

    override fun hashCode(): Int = items.hashCode()

    override fun toString(): String = "Outfit($wire)"

    public companion object {
        public val NONE: Outfit = Outfit(emptyMap())

        /** The items in order; a later item replaces an earlier one in the same slot. */
        public fun of(items: Iterable<Item>): Outfit = Outfit(items.associateBy { it.slot })

        /** Unknown names are skipped, so an older app reads a newer outfit as well as it can. */
        public fun parse(wire: String?): Outfit = of(wire.orEmpty().split(',').mapNotNull { Item.of(it.trim()) })
    }
}

/** Ready-made outfits for who the user is, so the AI can dress Buddy in one word. */
public enum class Look(
    public val items: List<Item>,
) {
    PLAIN(emptyList()),
    BASKETBALL(listOf(Item.CAP, Item.JERSEY, Item.BASKETBALL)),
    DOCTOR(listOf(Item.LAB_COAT, Item.STETHOSCOPE, Item.ROUND_GLASSES)),
    CHEF(listOf(Item.CHEF_HAT, Item.APRON, Item.SCARF)),
    GAMER(listOf(Item.HEADPHONES, Item.CONTROLLER)),
    STUDENT(listOf(Item.GRAD_CAP, Item.ROUND_GLASSES, Item.BOOK)),
    BUILDER(listOf(Item.HARD_HAT, Item.SAFETY_VEST)),
    OFFICE(listOf(Item.NECKTIE, Item.ROUND_GLASSES, Item.COFFEE)),
    PARTY(listOf(Item.PARTY_HAT, Item.BOW_TIE)),
    ;

    public val wire: String get() = name.lowercase(Locale.ROOT)

    public val outfit: Outfit get() = Outfit.of(items)

    public companion object {
        public fun of(wire: String?): Look? = entries.firstOrNull { it.wire == wire }
    }
}
