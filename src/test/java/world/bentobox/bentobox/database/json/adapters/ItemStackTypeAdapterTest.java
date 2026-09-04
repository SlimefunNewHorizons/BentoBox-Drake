package world.bentobox.bentobox.database.json.adapters;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.StringReader;
import java.io.StringWriter;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

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

    @Test
    void nativeParserRunsBeforeLegacyTypeInspection() throws Exception {
        String serialized = "is:\n  schema_version: 1\n  id: minecraft:diamond_sword\n"
                + "  components:\n    minecraft:attribute_modifiers:\n"
                + "    - type: minecraft:attack_damage\n";
        ItemStack expected = mock(ItemStack.class);

        try (MockedConstruction<YamlConfiguration> construction = mockConstruction(YamlConfiguration.class,
                (yaml, context) -> when(yaml.getItemStack("is")).thenReturn(expected))) {
            String jsonString = new Gson().toJson(serialized);
            ItemStack restored = new ItemStackTypeAdapter().read(new JsonReader(new StringReader(jsonString)));

            assertSame(expected, restored);
            verify(construction.constructed().getFirst()).loadFromString(serialized);
        }
    }

    @Test
    void emptyAirSerializesWithoutConsultingLogger() throws Exception {
        ItemStack empty = mock(ItemStack.class);
        ItemStack normalized = mock(ItemStack.class);
        when(empty.getType()).thenReturn(Material.AIR);
        when(empty.getAmount()).thenReturn(0);
        when(empty.clone()).thenReturn(normalized);
        when(normalized.getType()).thenReturn(Material.AIR);
        when(normalized.getAmount()).thenReturn(1);
        StringWriter target = new StringWriter();

        try (MockedConstruction<YamlConfiguration> construction = mockConstruction(YamlConfiguration.class,
                (yaml, context) -> when(yaml.saveToString()).thenReturn("is: AIR"));
                JsonWriter writer = new JsonWriter(target)) {
            assertDoesNotThrow(() -> new ItemStackTypeAdapter().write(writer, empty));
            writer.flush();

            YamlConfiguration yaml = construction.constructed().getFirst();
            verify(yaml).set(anyString(), org.mockito.ArgumentMatchers.argThat(value -> {
                ItemStack persisted = (ItemStack) value;
                return persisted.getType().isAir() && persisted.getAmount() == 1;
            }));
        }

        assertEquals(new Gson().toJson("is: AIR"), target.toString());
    }
}
