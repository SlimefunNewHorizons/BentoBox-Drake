package world.bentobox.bentobox.database.json.adapters;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ItemStackTypeAdapterTest {

    @Test
    void emptyAirIsAnExpectedLowAmountSentinel() {
        assertFalse(ItemStackTypeAdapter.isUnexpectedLowAmount(Material.AIR, 0));
    }

    @Test
    void zeroAmountRealItemRemainsUnexpected() {
        assertTrue(ItemStackTypeAdapter.isUnexpectedLowAmount(Material.STONE, 0));
    }

    @Test
    void positiveAmountRealItemIsValid() {
        assertFalse(ItemStackTypeAdapter.isUnexpectedLowAmount(Material.STONE, 1));
    }
}
