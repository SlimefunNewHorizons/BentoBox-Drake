package world.bentobox.bentobox.api.commands.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.eclipse.jdt.annotation.Nullable;

import world.bentobox.bentobox.api.commands.CompositeCommand;
import world.bentobox.bentobox.api.commands.ConfirmableCommand;
import world.bentobox.bentobox.api.events.island.IslandEvent;
import world.bentobox.bentobox.api.localization.TextVariables;
import world.bentobox.bentobox.api.user.User;
import world.bentobox.bentobox.database.objects.Island;
import world.bentobox.bentobox.managers.RanksManager;
import world.bentobox.bentobox.util.Util;

public class AdminRegisterCommand extends ConfirmableCommand {

    private Island island;
    private Location closestIsland;
    private @Nullable UUID targetUUID;

    public AdminRegisterCommand(CompositeCommand parent) {
        super(parent, "register");
    }

    @Override
    public void setup() {
        setPermission("admin.register");
        // Not player-only: the console can register an island by giving its x,y,z,
        // which is the only way to undo an admin delete when no op is in-game.
        setParametersHelp("commands.admin.register.parameters");
        setDescription("commands.admin.register.description");
    }

    @Override
    public boolean canExecute(User user, String label, List<String> args) {
        // If args are not right, show help
        if (args.isEmpty() || args.size() > 2) {
            showHelp(this, user);
            return false;
        }
        // Get target
        targetUUID = Util.getUUID(args.getFirst());
        if (targetUUID == null) {
            user.sendMessage("general.errors.unknown-player", TextVariables.NAME, args.getFirst());
            return false;
        }
        // Work out which location is being registered: the given x,y,z or where the player stands
        Location refLocation = getReferenceLocation(user, args);
        if (refLocation == null) {
            return false;
        }
        // Check if this spot is still being deleted
        closestIsland = Util.getClosestIsland(refLocation);
        if (getPlugin().getIslandDeletionManager().inDeletion(closestIsland)) {
            user.sendMessage("commands.admin.register.in-deletion");
            return false;
        }
        // Check if island is owned
        Optional<Island> opIsland = getIslands().getIslandAt(refLocation);
        if (opIsland.isEmpty()) {
            // Reserve spot
            this.askConfirmation(user, user.getTranslation("commands.admin.register.no-island-here"),
                    () -> reserve(user, args.getFirst()));
            return false;
        }
        island = opIsland.get();
        if (targetUUID.equals(island.getOwner())) {
            user.sendMessage("commands.admin.register.already-owned");
            return false;
        }
        // Check if island is spawn
        if (island.isSpawn()) {
            askConfirmation(user, user.getTranslation("commands.admin.register.island-is-spawn"),
                    () -> register(user, args.getFirst()));
            return false;
        }

        return true;
    }

    /**
     * Works out the location that is being registered. If x,y,z is given, that is
     * used, otherwise the user's own location is used. The console must give x,y,z
     * because it is not standing anywhere.
     *
     * @param user user running the command
     * @param args command arguments
     * @return the location to register, or {@code null} if it could not be resolved,
     *         in which case the user has already been told why
     *
     * @implNote the "specify island location" message is borrowed from the sibling
     *           unregister command on purpose: locale files already on disk are never
     *           refreshed from the jar ({@code LocalesManager#copyFile}), so a brand
     *           new key would render as its own reference on an existing server.
     */
    @Nullable
    private Location getReferenceLocation(User user, List<String> args) {
        if (args.size() == 2) {
            if (args.get(1).equalsIgnoreCase("help")) {
                showHelp(this, user);
                return null;
            }
            String[] xyz = args.get(1).split(",");
            if (xyz.length != 3) {
                user.sendMessage("commands.admin.unregister.errors.specify-island-location");
                return null;
            }
            try {
                return new Location(getWorld(), Integer.parseInt(xyz[0].trim()),
                        Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
            } catch (NumberFormatException e) {
                user.sendMessage("commands.admin.unregister.errors.specify-island-location");
                return null;
            }
        }
        if (!user.isPlayer()) {
            // The console is not standing on an island, so it has to say where to look
            user.sendMessage("commands.admin.unregister.errors.specify-island-location");
            return null;
        }
        if (!getWorld().equals(user.getWorld())) {
            user.sendMessage("general.errors.wrong-world");
            return null;
        }
        return user.getLocation();
    }

    @Override
    public boolean execute(User user, String label, List<String> args) {
        register(user, args.getFirst());
        return true;
    }

    /**
     * Reserve a spot for a target
     * @param user user doing the reserving
     * @param targetName target name
     */
    void reserve(User user, String targetName) {
        Objects.requireNonNull(closestIsland);
        Objects.requireNonNull(targetUUID);
        // Island does not exist - this is a reservation
        // Make island here
        Island i = getIslands().createIsland(closestIsland, targetUUID);
        if (i == null) {
            user.sendMessage("commands.admin.register.cannot-make-island");
            return;
        }
        getIslands().setOwner(user, targetUUID, i, RanksManager.VISITOR_RANK);
        i.setReserved(true);
        i.getCenter().getBlock().setType(Material.BEDROCK);
        user.sendMessage("commands.admin.register.reserved-island", TextVariables.XYZ,
                Util.xyz(i.getCenter().toVector()), TextVariables.NAME, targetName);
        // Build and fire event
        IslandEvent.builder().island(i).location(i.getCenter()).reason(IslandEvent.Reason.RESERVED)
                .involvedPlayer(targetUUID).admin(true).build();
    }

    /**
     * Register the island to a target
     * @param user user doing the registering
     * @param targetName name of target
     */
    void register(User user, String targetName) {
        Objects.requireNonNull(closestIsland);
        Objects.requireNonNull(targetUUID);
        Objects.requireNonNull(island);
        // Island exists
        getIslands().setOwner(user, targetUUID, island, RanksManager.VISITOR_RANK);
        if (island.isSpawn()) {
            getIslands().clearSpawn(island.getWorld());
        }
        // Remove deletion status if it has been assigned. Both flags must be cleared:
        // setDeleted covers a deletion in progress, setDeletable covers the soft-delete
        // that /[admin] delete and /island reset leave behind. While deletable is set,
        // every protection flag is denied on the island (see FlagListener) and the
        // region files are still queued for the housekeeping purge.
        island.setDeleted(false);
        island.setDeletable(false);
        user.sendMessage("commands.admin.register.registered-island", TextVariables.XYZ,
                Util.xyz(island.getCenter().toVector()), TextVariables.NAME, targetName);
        user.sendMessage("general.success");
        // Build and call event
        IslandEvent.builder().island(island).location(island.getCenter()).reason(IslandEvent.Reason.REGISTERED)
                .involvedPlayer(targetUUID).admin(true).build();
        IslandEvent.builder().island(island).involvedPlayer(targetUUID).admin(true)
                .reason(IslandEvent.Reason.RANK_CHANGE).rankChange(RanksManager.VISITOR_RANK, RanksManager.OWNER_RANK)
                .build();
    }

    @Override
    public Optional<List<String>> tabComplete(User user, String alias, List<String> args) {
        String lastArg = !args.isEmpty() ? args.getLast() : "";
        if (args.size() != 2) {
            // Don't show every player on the server. Require at least the first letter,
            // and don't offer player names where the x,y,z goes.
            return Optional.empty();
        }
        List<String> options = new ArrayList<>(Util.getOnlinePlayerList(user));
        return Optional.of(Util.tabLimit(options, lastArg));
    }

}
