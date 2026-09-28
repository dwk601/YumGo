package com.dwk.yumgo.data

/**
 * A premade food default. [expiryDays] is a suggestion counted from the day the item is added,
 * not a food-safety guarantee: the user still reads the date on the pack.
 */
data class FoodPreset(val id: String, val name: String, val expiryDays: Int)

/** Longest suggested shelf life a preset may carry, in days. */
internal const val MaxPresetExpiryDays = 365

/** Longest preset name a preset may carry. */
internal const val MaxPresetNameLength = 40

/**
 * Groceries that ship with the app. Ids are stable, so a saved edit keeps pointing at the same
 * food when the catalog grows. The list is a starting point only: the settings screen edits
 * copies of it, and editing a preset never touches items already in the fridge.
 */
object FoodPresetCatalog {
  val entries: List<FoodPreset> =
    listOf(
      FoodPreset("milk", "Milk", 7),
      FoodPreset("eggs", "Eggs", 14),
      FoodPreset("chicken", "Chicken", 3),
      FoodPreset("ground_beef", "Ground beef", 3),
      FoodPreset("fish", "Fish", 2),
      FoodPreset("yogurt", "Yogurt", 10),
      FoodPreset("cheese", "Cheese", 21),
      FoodPreset("butter", "Butter", 30),
      FoodPreset("bread", "Bread", 5),
      FoodPreset("rice", "Rice", 180),
      FoodPreset("pasta", "Pasta", 180),
      FoodPreset("salad_greens", "Salad greens", 5),
      FoodPreset("tomatoes", "Tomatoes", 7),
      FoodPreset("carrots", "Carrots", 14),
      FoodPreset("frozen_peas", "Frozen peas", 120),
    )
}
