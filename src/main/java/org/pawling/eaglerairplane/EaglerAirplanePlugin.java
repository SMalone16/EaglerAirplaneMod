package org.pawling.eaglerairplane;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class EaglerAirplanePlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String PREFIX = ChatColor.AQUA + "[Plane] " + ChatColor.RESET;

    private final Map<UUID, PlaneSession> sessions = new HashMap<>();
    private final Map<UUID, EngineStart> engineStarts = new HashMap<>();

    private NamespacedKey planeKitKey;
    private NamespacedKey recipeKey;
    private BukkitTask flightTicker;
    private long tickCounter;

    private double maxHealth;
    private int engineStartSeconds;
    private double startupMaxDistance;

    private double startingThrottle;
    private double accelerationPerTick;
    private double strafeAccelerationFactor;
    private double coastDecayPerTick;
    private double brakePerTick;
    private float minFlySpeed;
    private float maxFlySpeed;
    private double landingMaxThrottle;
    private double groundCheckDistance;

    private double collisionBaseDamage;
    private double collisionMaxSpeedDamage;
    private double catastrophicThrottle;
    private int collisionCooldownTicks;
    private double collisionScanBaseDistance;
    private double collisionScanMovementMultiplier;

    private double crashPlayerDamage;
    private boolean particlesEnabled;
    private boolean soundsEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        planeKitKey = new NamespacedKey(this, "plane_kit");
        recipeKey = new NamespacedKey(this, "plane_kit_recipe");

        registerPlaneRecipe();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("plane") != null) {
            getCommand("plane").setExecutor(this);
            getCommand("plane").setTabCompleter(this);
        }

        flightTicker = Bukkit.getScheduler().runTaskTimer(this, this::flightTick, 1L, 1L);
        getLogger().info("EaglerAirplaneMod enabled. Plane Mode is ready.");
    }

    @Override
    public void onDisable() {
        if (flightTicker != null) {
            flightTicker.cancel();
        }

        for (EngineStart start : new ArrayList<>(engineStarts.values())) {
            start.task.cancel();
        }
        engineStarts.clear();

        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            PlaneSession session = sessions.remove(uuid);
            if (player != null && session != null) {
                restoreFlightState(player, session);
                givePlaneKit(player);
                player.sendMessage(PREFIX + ChatColor.YELLOW + "Server stopping: your Plane Kit was returned.");
            }
        }

        if (recipeKey != null) {
            Bukkit.removeRecipe(recipeKey);
        }
    }

    private void loadSettings() {
        reloadConfig();

        maxHealth = Math.max(1.0, getConfig().getDouble("plane.max-health", 100.0));
        engineStartSeconds = Math.max(1, getConfig().getInt("plane.engine-start-seconds", 4));
        startupMaxDistance = Math.max(1.0, getConfig().getDouble("plane.startup-max-distance", 4.0));

        startingThrottle = clamp(getConfig().getDouble("flight.starting-throttle", 0.10), 0.0, 1.0);
        accelerationPerTick = Math.max(0.0001, getConfig().getDouble("flight.acceleration-per-tick", 0.012));
        strafeAccelerationFactor = clamp(getConfig().getDouble("flight.strafe-acceleration-factor", 0.45), 0.0, 1.0);
        coastDecayPerTick = Math.max(0.0, getConfig().getDouble("flight.coast-decay-per-tick", 0.003));
        brakePerTick = Math.max(0.001, getConfig().getDouble("flight.brake-per-tick", 0.030));

        minFlySpeed = (float) clamp(getConfig().getDouble("flight.min-fly-speed", 0.05), 0.01, 0.90);
        maxFlySpeed = (float) clamp(getConfig().getDouble("flight.max-fly-speed", 0.28), minFlySpeed, 0.95);

        landingMaxThrottle = clamp(getConfig().getDouble("flight.landing-max-throttle", 0.18), 0.0, 1.0);
        groundCheckDistance = Math.max(0.5, getConfig().getDouble("flight.ground-check-distance", 2.2));

        collisionBaseDamage = Math.max(0.0, getConfig().getDouble("collision.base-damage", 8.0));
        collisionMaxSpeedDamage = Math.max(0.0, getConfig().getDouble("collision.max-speed-damage", 55.0));
        catastrophicThrottle = clamp(getConfig().getDouble("collision.catastrophic-throttle", 0.86), 0.1, 1.0);
        collisionCooldownTicks = Math.max(1, getConfig().getInt("collision.cooldown-ticks", 12));
        collisionScanBaseDistance = Math.max(0.2, getConfig().getDouble("collision.scan-base-distance", 0.75));
        collisionScanMovementMultiplier = Math.max(0.0, getConfig().getDouble("collision.scan-movement-multiplier", 4.0));

        crashPlayerDamage = Math.max(0.0, getConfig().getDouble("crash.player-damage", 12.0));

        particlesEnabled = getConfig().getBoolean("effects.particles", true);
        soundsEnabled = getConfig().getBoolean("effects.sounds", true);
    }

    private void registerPlaneRecipe() {
        Bukkit.removeRecipe(recipeKey);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createPlaneKit());
        recipe.shape("IDI", "RFR", "IDI");
        recipe.setIngredient('I', Material.IRON_BLOCK);
        recipe.setIngredient('D', Material.DIAMOND);
        recipe.setIngredient('R', Material.REDSTONE_BLOCK);
        recipe.setIngredient('F', Material.FURNACE);
        Bukkit.addRecipe(recipe);
    }

    private ItemStack createPlaneKit() {
        ItemStack kit = new ItemStack(Material.MINECART);
        ItemMeta meta = kit.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Plane Kit");
        meta.setLore(List.of(
                ChatColor.GRAY + "Evan + Liam's Plane System",
                ChatColor.WHITE + "Right-click to start the engine.",
                ChatColor.WHITE + "Startup takes " + engineStartSeconds + " seconds.",
                ChatColor.YELLOW + "Move to accelerate. Crouch to brake/land.",
                ChatColor.RED + "High-speed crashes destroy the plane."
        ));
        meta.getPersistentDataContainer().set(planeKitKey, PersistentDataType.BYTE, (byte) 1);
        kit.setItemMeta(meta);
        return kit;
    }

    private boolean isPlaneKit(ItemStack item) {
        if (item == null || item.getType() != Material.MINECART || !item.hasItemMeta()) {
            return false;
        }
        Byte value = item.getItemMeta().getPersistentDataContainer().get(planeKitKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUsePlaneKit(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (!isPlaneKit(item)) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage(PREFIX + ChatColor.RED + "Plane Mode cannot start in spectator mode.");
            return;
        }

        if (sessions.containsKey(player.getUniqueId())) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "You are already flying a plane.");
            return;
        }

        if (engineStarts.containsKey(player.getUniqueId())) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "The engine is already starting.");
            return;
        }

        beginEngineStart(player);
    }

    private void beginEngineStart(Player player) {
        UUID uuid = player.getUniqueId();
        Location origin = player.getLocation().clone();
        int totalTicks = engineStartSeconds * 20;

        player.sendMessage(PREFIX + ChatColor.YELLOW + "Engine starting. Stay nearby for " + engineStartSeconds + " seconds...");
        player.sendTitle(ChatColor.GOLD + "ENGINE START", ChatColor.GRAY + "Do not move away", 5, 25, 5);

        BukkitRunnable runnable = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                EngineStart start = engineStarts.get(uuid);
                Player current = Bukkit.getPlayer(uuid);

                if (start == null || current == null || !current.isOnline()) {
                    cancel();
                    return;
                }

                if (!hasPlaneKit(current)) {
                    cancelEngineStart(current, ChatColor.RED + "Startup cancelled: the Plane Kit is no longer in your inventory.");
                    cancel();
                    return;
                }

                if (!current.getWorld().equals(origin.getWorld())
                        || current.getLocation().distanceSquared(origin) > startupMaxDistance * startupMaxDistance) {
                    cancelEngineStart(current, ChatColor.RED + "Startup cancelled: you moved too far from the starting point.");
                    cancel();
                    return;
                }

                int secondsLeft = Math.max(0, (int) Math.ceil((totalTicks - elapsed) / 20.0));
                if (secondsLeft > 0 && elapsed % 20 == 0) {
                    current.sendActionBar(Component.text("Engine start: " + secondsLeft + "...", NamedTextColor.GOLD));
                    if (soundsEnabled) {
                        current.getWorld().playSound(current.getLocation(), Sound.BLOCK_PISTON_EXTEND, 0.55f, 0.75f + (elapsed / (float) totalTicks));
                    }
                    if (particlesEnabled) {
                        current.getWorld().spawnParticle(Particle.CLOUD, current.getLocation().add(0, 0.4, 0), 5, 0.25, 0.15, 0.25, 0.01);
                    }
                }

                if (elapsed >= totalTicks) {
                    if (!consumeOnePlaneKit(current)) {
                        cancelEngineStart(current, ChatColor.RED + "Startup cancelled: Plane Kit not found.");
                        cancel();
                        return;
                    }

                    engineStarts.remove(uuid);
                    cancel();
                    enterPlaneMode(current);
                    return;
                }

                elapsed += 10;
            }
        };

        BukkitTask task = runnable.runTaskTimer(this, 0L, 10L);
        engineStarts.put(uuid, new EngineStart(origin, task));
    }

    private void cancelEngineStart(Player player, String reason) {
        EngineStart start = engineStarts.remove(player.getUniqueId());
        if (start != null) {
            start.task.cancel();
        }
        player.sendMessage(PREFIX + reason);
        player.sendActionBar(Component.empty());
        if (soundsEnabled) {
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_PISTON_CONTRACT, 0.5f, 0.7f);
        }
    }

    private void enterPlaneMode(Player player) {
        PlaneSession session = new PlaneSession(
                player.getAllowFlight(),
                player.isFlying(),
                player.getFlySpeed(),
                maxHealth,
                startingThrottle,
                player.getLocation().clone()
        );

        sessions.put(player.getUniqueId(), session);

        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(throttleToFlySpeed(session.throttle));
        player.setFallDistance(0);

        player.sendTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "PLANE MODE",
                ChatColor.WHITE + "Look + move to fly | Crouch to brake", 5, 35, 10);
        player.sendMessage(PREFIX + ChatColor.GREEN + "Plane online! Move to accelerate. Crouch to brake and land.");

        if (soundsEnabled) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_MINECART_RIDING, 0.8f, 1.25f);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        EngineStart start = engineStarts.get(uuid);
        if (start != null && event.getTo() != null) {
            if (!event.getTo().getWorld().equals(start.origin.getWorld())
                    || event.getTo().distanceSquared(start.origin) > startupMaxDistance * startupMaxDistance) {
                cancelEngineStart(player, ChatColor.RED + "Startup cancelled: you moved too far from the starting point.");
                return;
            }
        }

        PlaneSession session = sessions.get(uuid);
        if (session == null || event.getTo() == null) {
            return;
        }

        Vector movement = event.getTo().toVector().subtract(event.getFrom().toVector());
        double distance = movement.length();

        if (distance > 0.001) {
            session.lastMoveDistance = distance;
            session.lastMoveVector = movement.clone();
            session.lastMoveNanos = System.nanoTime();

            Vector look = event.getTo().getDirection();
            if (look.lengthSquared() > 0.0001) {
                look.normalize();
            }
            session.forwardDot = movement.clone().normalize().dot(look);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        PlaneSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            session.braking = event.isSneaking();
        }
    }

    @EventHandler
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }

        if (!event.isFlying()) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(this, () -> {
                Player player = event.getPlayer();
                if (sessions.containsKey(player.getUniqueId())) {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (engineStarts.containsKey(player.getUniqueId())) {
            cancelEngineStart(player, ChatColor.RED + "Startup cancelled because you took damage.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        EngineStart start = engineStarts.remove(uuid);
        if (start != null) {
            start.task.cancel();
        }

        PlaneSession session = sessions.remove(uuid);
        if (session != null) {
            restoreFlightState(event.getPlayer(), session);
            givePlaneKit(event.getPlayer());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();

        EngineStart start = engineStarts.remove(uuid);
        if (start != null) {
            start.task.cancel();
        }

        PlaneSession session = sessions.remove(uuid);
        if (session != null) {
            event.deathMessage(Component.text(player.getName() + "'s plane was lost in the crash."));
        }
    }

    private void flightTick() {
        tickCounter++;

        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            PlaneSession session = sessions.get(uuid);

            if (player == null || session == null || !player.isOnline() || player.isDead()) {
                sessions.remove(uuid);
                continue;
            }

            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
            if (!player.isFlying()) {
                player.setFlying(true);
            }

            if (session.collisionCooldown > 0) {
                session.collisionCooldown--;
            }

            boolean braking = session.braking || player.isSneaking();
            boolean recentMotion = System.nanoTime() - session.lastMoveNanos < 175_000_000L
                    && session.lastMoveDistance > 0.01;

            if (braking) {
                session.throttle -= brakePerTick;
            } else if (recentMotion) {
                if (session.forwardDot > 0.20) {
                    session.throttle += accelerationPerTick;
                } else if (session.forwardDot < -0.20) {
                    session.throttle -= brakePerTick * 0.55;
                } else {
                    session.throttle += accelerationPerTick * strafeAccelerationFactor;
                }
            } else {
                session.throttle -= coastDecayPerTick;
            }

            session.throttle = clamp(session.throttle, 0.0, 1.0);
            player.setFlySpeed(throttleToFlySpeed(session.throttle));
            player.setFallDistance(0);

            if (braking && session.throttle <= landingMaxThrottle && isNearGround(player)) {
                finishFlight(player, session, true,
                        ChatColor.GREEN + "Touchdown! Plane Kit returned.");
                continue;
            }

            if (isOpenSpace(player.getLocation())) {
                session.lastSafeLocation = player.getLocation().clone();
            }

            if (session.collisionCooldown == 0 && recentMotion && detectCollision(player, session)) {
                handleCollision(player, session);
                if (!sessions.containsKey(uuid)) {
                    continue;
                }
            }

            if (tickCounter % 5 == 0) {
                int throttlePercent = (int) Math.round(session.throttle * 100.0);
                String hud = String.format(Locale.US, "Plane HP: %.0f/%.0f   Throttle: %d%%   Crouch = brake/land",
                        session.health, maxHealth, throttlePercent);
                player.sendActionBar(Component.text(hud,
                        session.health <= maxHealth * 0.30 ? NamedTextColor.RED : NamedTextColor.AQUA));
            }

            if (particlesEnabled && tickCounter % 4 == 0 && session.throttle > 0.08) {
                Location trail = player.getLocation().add(0, 0.65, 0);
                Vector back = player.getLocation().getDirection().normalize().multiply(-0.8);
                trail.add(back);
                player.getWorld().spawnParticle(Particle.CLOUD, trail, 2, 0.10, 0.08, 0.10, 0.005);
            }

            if (soundsEnabled && tickCounter % 16 == 0 && session.throttle > 0.10) {
                float pitch = (float) (0.75 + session.throttle * 0.65);
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_ELYTRA_FLYING, 0.35f, pitch);
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_MINECART_RIDING, 0.18f, pitch);
            }
        }
    }

    private boolean detectCollision(Player player, PlaneSession session) {
        Vector direction = session.lastMoveVector.clone();
        if (direction.lengthSquared() < 0.0001) {
            direction = player.getLocation().getDirection();
        }

        if (direction.lengthSquared() < 0.0001) {
            return false;
        }

        direction.normalize();

        double scanDistance = collisionScanBaseDistance
                + (session.lastMoveDistance * collisionScanMovementMultiplier)
                + (session.throttle * 0.55);

        Location origin = player.getLocation().clone().add(0, 0.9, 0);
        RayTraceResult result = player.getWorld().rayTraceBlocks(
                origin,
                direction,
                scanDistance,
                FluidCollisionMode.NEVER,
                true
        );

        return result != null && result.getHitBlock() != null && result.getHitBlock().getType().isSolid();
    }

    private void handleCollision(Player player, PlaneSession session) {
        double impactThrottle = session.throttle;

        if (impactThrottle >= catastrophicThrottle) {
            handleCrash(player, session,
                    "Catastrophic high-speed impact (" + (int) Math.round(impactThrottle * 100.0) + "% throttle)");
            return;
        }

        double damage = collisionBaseDamage + collisionMaxSpeedDamage * impactThrottle * impactThrottle;
        session.health = Math.max(0.0, session.health - damage);
        session.throttle *= 0.35;
        session.collisionCooldown = collisionCooldownTicks;

        if (session.lastSafeLocation != null
                && session.lastSafeLocation.getWorld() != null
                && session.lastSafeLocation.getWorld().equals(player.getWorld())) {
            player.teleport(session.lastSafeLocation);
        }

        Vector rebound = session.lastMoveVector.clone();
        if (rebound.lengthSquared() > 0.0001) {
            rebound.normalize().multiply(-0.20).setY(0.12);
            player.setVelocity(rebound);
        }

        if (soundsEnabled) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.55f, 1.45f);
        }
        if (particlesEnabled) {
            player.getWorld().spawnParticle(Particle.FLAME, player.getLocation().add(0, 0.8, 0),
                    10, 0.35, 0.30, 0.35, 0.02);
        }

        player.sendMessage(PREFIX + ChatColor.RED + String.format(Locale.US,
                "Impact! -%.0f HP. Plane health: %.0f/%.0f", damage, session.health, maxHealth));

        if (session.health <= 0.0) {
            handleCrash(player, session, "Plane health reached zero");
        }
    }

    private void handleCrash(Player player, PlaneSession expectedSession, String reason) {
        PlaneSession session = sessions.remove(player.getUniqueId());
        if (session == null || session != expectedSession) {
            return;
        }

        Location crashLocation = player.getLocation().clone();
        if (session.lastSafeLocation != null
                && session.lastSafeLocation.getWorld() != null
                && session.lastSafeLocation.getWorld().equals(player.getWorld())) {
            player.teleport(session.lastSafeLocation);
            crashLocation = player.getLocation().clone();
        }

        restoreFlightState(player, session);

        World world = player.getWorld();
        if (soundsEnabled) {
            world.playSound(crashLocation, Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 0.65f);
        }
        if (particlesEnabled) {
            world.spawnParticle(Particle.EXPLOSION_EMITTER, crashLocation.clone().add(0, 0.8, 0), 1);
            world.spawnParticle(Particle.FLAME, crashLocation.clone().add(0, 0.8, 0),
                    35, 0.75, 0.75, 0.75, 0.08);
        }

        player.setVelocity(new Vector(0, 0.35, 0));
        player.setFallDistance(0);
        if (crashPlayerDamage > 0.0) {
            player.damage(crashPlayerDamage);
        }

        player.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "PLANE DESTROYED",
                ChatColor.RED + "Craft another Plane Kit", 5, 45, 15);
        player.sendMessage(PREFIX + ChatColor.RED + "CRASH: " + reason + ". The Plane Kit was destroyed.");
    }

    private void finishFlight(Player player, PlaneSession expectedSession, boolean returnKit, String message) {
        PlaneSession session = sessions.remove(player.getUniqueId());
        if (session == null || session != expectedSession) {
            return;
        }

        restoreFlightState(player, session);
        player.setFallDistance(0);

        if (returnKit) {
            givePlaneKit(player);
        }

        player.sendActionBar(Component.empty());
        player.sendMessage(PREFIX + message);

        if (soundsEnabled) {
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_PISTON_CONTRACT, 0.45f, 0.9f);
        }
    }

    private void restoreFlightState(Player player, PlaneSession session) {
        try {
            player.setFlying(false);
            player.setFlySpeed(session.previousFlySpeed);
            player.setAllowFlight(session.previousAllowFlight);
            if (session.previousAllowFlight && session.previousFlying) {
                player.setFlying(true);
            }
        } catch (IllegalArgumentException ignored) {
            player.setFlying(false);
            player.setAllowFlight(session.previousAllowFlight);
        }
    }

    private boolean isNearGround(Player player) {
        Location start = player.getLocation().clone().add(0, 0.15, 0);
        RayTraceResult result = player.getWorld().rayTraceBlocks(
                start,
                new Vector(0, -1, 0),
                groundCheckDistance,
                FluidCollisionMode.ALWAYS,
                true
        );
        return result != null && result.getHitBlock() != null && result.getHitBlock().getType().isSolid();
    }

    private boolean isOpenSpace(Location feet) {
        return feet.getBlock().isPassable()
                && feet.clone().add(0, 1, 0).getBlock().isPassable();
    }

    private float throttleToFlySpeed(double throttle) {
        return (float) (minFlySpeed + (maxFlySpeed - minFlySpeed) * clamp(throttle, 0.0, 1.0));
    }

    private boolean hasPlaneKit(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isPlaneKit(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean consumeOnePlaneKit(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isPlaneKit(item)) {
                continue;
            }

            if (item.getAmount() <= 1) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(slot, item);
            }
            return true;
        }

        return false;
    }

    private void givePlaneKit(Player player) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(createPlaneKit());
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private boolean hasAdmin(CommandSender sender) {
        return sender.hasPermission("eaglerairplane.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(PREFIX + ChatColor.WHITE + "/plane status" + ChatColor.GRAY + " - show your plane status");
            sender.sendMessage(PREFIX + ChatColor.WHITE + "/plane land" + ChatColor.GRAY + " - land when low and slow");
            if (hasAdmin(sender)) {
                sender.sendMessage(PREFIX + ChatColor.WHITE + "/plane give [player]" + ChatColor.GRAY + " - give a Plane Kit");
                sender.sendMessage(PREFIX + ChatColor.WHITE + "/plane reload" + ChatColor.GRAY + " - reload tuning values");
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                Player target;
                if (args.length >= 2) {
                    if (!hasAdmin(sender)) {
                        sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to inspect other players.");
                        return true;
                    }
                    target = Bukkit.getPlayerExact(args[1]);
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Console usage: /plane status <player>");
                    return true;
                }

                if (target == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Player not found.");
                    return true;
                }

                PlaneSession session = sessions.get(target.getUniqueId());
                if (session == null) {
                    sender.sendMessage(PREFIX + target.getName() + " is not in Plane Mode.");
                } else {
                    sender.sendMessage(PREFIX + String.format(Locale.US,
                            "%s: %.0f/%.0f HP, %d%% throttle",
                            target.getName(), session.health, maxHealth, (int) Math.round(session.throttle * 100.0)));
                }
                return true;
            }
            case "land" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Only a player can land a plane.");
                    return true;
                }

                PlaneSession session = sessions.get(player.getUniqueId());
                if (session == null) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW + "You are not in Plane Mode.");
                    return true;
                }

                if (!isNearGround(player)) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW + "Get closer to the ground before landing.");
                    return true;
                }

                if (session.throttle > landingMaxThrottle * 1.25) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW + "Too fast to land. Crouch to brake first.");
                    return true;
                }

                finishFlight(player, session, true, ChatColor.GREEN + "Manual landing complete. Plane Kit returned.");
                return true;
            }
            case "give" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission.");
                    return true;
                }

                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Console usage: /plane give <player>");
                    return true;
                }

                if (target == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Player not found.");
                    return true;
                }

                givePlaneKit(target);
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Plane Kit given to " + target.getName() + ".");
                if (!sender.equals(target)) {
                    target.sendMessage(PREFIX + ChatColor.GREEN + "You received a Plane Kit.");
                }
                return true;
            }
            case "reload" -> {
                if (!hasAdmin(sender)) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission.");
                    return true;
                }

                loadSettings();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Configuration reloaded.");
                return true;
            }
            default -> {
                sender.sendMessage(PREFIX + ChatColor.RED + "Unknown subcommand. Use /plane for help.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("status", "land"));
            if (hasAdmin(sender)) {
                options.add("give");
                options.add("reload");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            options.removeIf(option -> !option.startsWith(prefix));
            return options;
        }

        if (args.length == 2 && hasAdmin(sender)
                && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("status"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(player.getName());
                }
            }
            return names;
        }

        return Collections.emptyList();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class EngineStart {
        private final Location origin;
        private final BukkitTask task;

        private EngineStart(Location origin, BukkitTask task) {
            this.origin = origin;
            this.task = task;
        }
    }

    private static final class PlaneSession {
        private final boolean previousAllowFlight;
        private final boolean previousFlying;
        private final float previousFlySpeed;

        private double health;
        private double throttle;
        private boolean braking;

        private long lastMoveNanos;
        private double lastMoveDistance;
        private double forwardDot;
        private Vector lastMoveVector = new Vector();
        private int collisionCooldown;
        private Location lastSafeLocation;

        private PlaneSession(boolean previousAllowFlight,
                             boolean previousFlying,
                             float previousFlySpeed,
                             double health,
                             double throttle,
                             Location lastSafeLocation) {
            this.previousAllowFlight = previousAllowFlight;
            this.previousFlying = previousFlying;
            this.previousFlySpeed = previousFlySpeed;
            this.health = health;
            this.throttle = throttle;
            this.lastSafeLocation = lastSafeLocation;
        }
    }
}
