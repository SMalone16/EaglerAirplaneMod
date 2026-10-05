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
    private double throttleIncreasePerTick;
    private double throttleDecreasePerTick;
    private double maxAirspeed;
    private double cruiseSpeed;
    private double takeoffSpeed;
    private double stallSpeed;
    private double groundAccelerationPerTick;
    private double airAccelerationPerTick;
    private double dragPerTick;
    private double rollingDragPerTick;
    private double groundBrakePerTick;
    private double climbDragPerTick;
    private double bankDragPerTick;
    private double groundSteeringResponse;
    private double airSteeringResponse;
    private double maxPitchDegrees;
    private double takeoffPitchDegrees;
    private double maxBankDegrees;
    private double bankPerYawDegree;
    private double bankResponse;
    private double gravityCompensation;
    private double stallSinkMax;
    private double bankLiftPenalty;
    private double landingMaxSpeed;
    private double landingMaxThrottle;
    private double landingMaxPitchDegrees;
    private double groundCheckDistance;
    private float planeWalkSpeed;

    private double collisionBaseDamage;
    private double collisionMaxSpeedDamage;
    private double catastrophicAirspeed;
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
        getLogger().info("EaglerAirplaneMod enabled with fixed-wing physics.");
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
                restorePlayerState(player, session);
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

        startingThrottle = clamp(getConfig().getDouble("flight.starting-throttle", 0.18), 0.0, 1.0);
        throttleIncreasePerTick = Math.max(0.0005, getConfig().getDouble("flight.throttle-increase-per-tick", 0.008));
        throttleDecreasePerTick = Math.max(0.0005, getConfig().getDouble("flight.throttle-decrease-per-tick", 0.012));

        maxAirspeed = Math.max(0.30, getConfig().getDouble("flight.max-airspeed", 1.05));
        cruiseSpeed = clamp(getConfig().getDouble("flight.cruise-speed", 0.72), 0.10, maxAirspeed);
        takeoffSpeed = clamp(getConfig().getDouble("flight.takeoff-speed", 0.48), 0.10, maxAirspeed);
        stallSpeed = clamp(getConfig().getDouble("flight.stall-speed", 0.34), 0.05, takeoffSpeed);

        groundAccelerationPerTick = Math.max(0.0005, getConfig().getDouble("flight.ground-acceleration-per-tick", 0.008));
        airAccelerationPerTick = Math.max(0.0005, getConfig().getDouble("flight.air-acceleration-per-tick", 0.005));
        dragPerTick = Math.max(0.0001, getConfig().getDouble("flight.drag-per-tick", 0.003));
        rollingDragPerTick = Math.max(0.0, getConfig().getDouble("flight.rolling-drag-per-tick", 0.0012));
        groundBrakePerTick = Math.max(0.001, getConfig().getDouble("flight.ground-brake-per-tick", 0.035));
        climbDragPerTick = Math.max(0.0, getConfig().getDouble("flight.climb-drag-per-tick", 0.004));
        bankDragPerTick = Math.max(0.0, getConfig().getDouble("flight.bank-drag-per-tick", 0.0015));

        groundSteeringResponse = clamp(getConfig().getDouble("flight.ground-steering-response", 0.18), 0.01, 1.0);
        airSteeringResponse = clamp(getConfig().getDouble("flight.air-steering-response", 0.08), 0.01, 1.0);
        maxPitchDegrees = clamp(getConfig().getDouble("flight.max-pitch-degrees", 35.0), 5.0, 75.0);
        takeoffPitchDegrees = clamp(getConfig().getDouble("flight.takeoff-pitch-degrees", 7.5), 1.0, maxPitchDegrees);
        maxBankDegrees = clamp(getConfig().getDouble("flight.max-bank-degrees", 45.0), 5.0, 80.0);
        bankPerYawDegree = Math.max(0.1, getConfig().getDouble("flight.bank-per-yaw-degree", 6.0));
        bankResponse = clamp(getConfig().getDouble("flight.bank-response", 0.18), 0.01, 1.0);

        gravityCompensation = clamp(getConfig().getDouble("flight.gravity-compensation", 0.08), 0.0, 0.20);
        stallSinkMax = clamp(getConfig().getDouble("flight.stall-sink-max", 0.14), 0.0, 0.50);
        bankLiftPenalty = clamp(getConfig().getDouble("flight.bank-lift-penalty", 0.025), 0.0, 0.20);

        landingMaxSpeed = clamp(getConfig().getDouble("flight.landing-max-speed", 0.52), 0.10, maxAirspeed);
        landingMaxThrottle = clamp(getConfig().getDouble("flight.landing-max-throttle", 0.35), 0.0, 1.0);
        landingMaxPitchDegrees = clamp(getConfig().getDouble("flight.landing-max-pitch-degrees", 15.0), 1.0, maxPitchDegrees);
        groundCheckDistance = Math.max(0.5, getConfig().getDouble("flight.ground-check-distance", 1.5));
        planeWalkSpeed = (float) clamp(getConfig().getDouble("flight.player-walk-speed", 0.02), 0.0, 1.0);

        collisionBaseDamage = Math.max(0.0, getConfig().getDouble("collision.base-damage", 8.0));
        collisionMaxSpeedDamage = Math.max(0.0, getConfig().getDouble("collision.max-speed-damage", 55.0));
        catastrophicAirspeed = clamp(getConfig().getDouble("collision.catastrophic-airspeed", 0.88), 0.10, maxAirspeed);
        collisionCooldownTicks = Math.max(1, getConfig().getInt("collision.cooldown-ticks", 12));
        collisionScanBaseDistance = Math.max(0.2, getConfig().getDouble("collision.scan-base-distance", 0.75));
        collisionScanMovementMultiplier = Math.max(0.0, getConfig().getDouble("collision.scan-movement-multiplier", 2.4));

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
                ChatColor.GRAY + "Evan + Liam's Fixed-Wing Plane",
                ChatColor.WHITE + "Right-click to start the engine.",
                ChatColor.WHITE + "Sprint = throttle up | Crouch = throttle down",
                ChatColor.YELLOW + String.format(Locale.US, "Rotate above %.2f speed by looking up.", takeoffSpeed),
                ChatColor.RED + "Stalls and high-speed crashes are real."
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

        if (!isPlaneKit(event.getItem())) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage(PREFIX + ChatColor.RED + "Plane Mode cannot start in spectator mode.");
            return;
        }

        if (sessions.containsKey(player.getUniqueId())) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "You are already operating a plane.");
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
                        current.getWorld().playSound(current.getLocation(), Sound.BLOCK_PISTON_EXTEND,
                                0.55f, 0.75f + (elapsed / (float) totalTicks));
                    }
                    if (particlesEnabled) {
                        current.getWorld().spawnParticle(Particle.CLOUD, current.getLocation().add(0, 0.4, 0),
                                5, 0.25, 0.15, 0.25, 0.01);
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
        Vector initialNose = calculateNose(player, false);

        PlaneSession session = new PlaneSession(
                player.getAllowFlight(),
                player.isFlying(),
                player.getFlySpeed(),
                player.getWalkSpeed(),
                maxHealth,
                startingThrottle,
                initialNose,
                player.getLocation().getYaw(),
                player.getLocation().clone()
        );

        sessions.put(player.getUniqueId(), session);

        player.setAllowFlight(true);
        player.setFlying(false);
        player.setWalkSpeed(planeWalkSpeed);
        player.setFallDistance(0);
        player.setVelocity(new Vector());

        player.sendTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "PLANE MODE",
                ChatColor.WHITE + "Sprint + throttle | Crouch - throttle | Look to steer", 5, 45, 10);
        player.sendMessage(PREFIX + ChatColor.GREEN
                + "Engine online. Build runway speed, then look up to rotate. The plane will not hover.");

        if (soundsEnabled) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_MINECART_RIDING, 0.8f, 1.05f);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        EngineStart start = engineStarts.get(event.getPlayer().getUniqueId());
        if (start == null || event.getTo() == null) {
            return;
        }

        if (!event.getTo().getWorld().equals(start.origin.getWorld())
                || event.getTo().distanceSquared(start.origin) > startupMaxDistance * startupMaxDistance) {
            cancelEngineStart(event.getPlayer(), ChatColor.RED + "Startup cancelled: you moved too far from the starting point.");
        }
    }

    @EventHandler
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            if (sessions.containsKey(player.getUniqueId())) {
                player.setAllowFlight(true);
                player.setFlying(false);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (engineStarts.containsKey(player.getUniqueId())) {
            cancelEngineStart(player, ChatColor.RED + "Startup cancelled because you took damage.");
        }

        if (sessions.containsKey(player.getUniqueId())
                && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
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
            restorePlayerState(event.getPlayer(), session);
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
            restorePlayerState(player, session);
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

            player.setAllowFlight(true);
            if (player.isFlying()) {
                player.setFlying(false);
            }
            if (Math.abs(player.getWalkSpeed() - planeWalkSpeed) > 0.0001f) {
                player.setWalkSpeed(planeWalkSpeed);
            }
            player.setFallDistance(0);

            if (session.collisionCooldown > 0) {
                session.collisionCooldown--;
            }

            updateThrottle(player, session);

            float currentYaw = player.getLocation().getYaw();
            double yawDelta = wrapDegrees(currentYaw - session.lastYaw);
            session.lastYaw = currentYaw;

            if (session.airborne) {
                double targetBank = clamp(yawDelta * bankPerYawDegree, -maxBankDegrees, maxBankDegrees);
                session.bankDegrees += (targetBank - session.bankDegrees) * bankResponse;
            } else {
                session.bankDegrees += (0.0 - session.bankDegrees) * bankResponse;
            }

            Vector desiredNose = calculateNose(player, session.airborne);
            double steeringResponse = session.airborne ? airSteeringResponse : groundSteeringResponse;
            if (session.airborne) {
                double speedAuthority = clamp(session.airspeed / Math.max(stallSpeed, 0.01), 0.35, 1.0);
                steeringResponse *= speedAuthority;
            }
            session.nose = blendDirection(session.nose, desiredNose, steeringResponse);

            updateAirspeed(session, player.isSneaking());

            if (!session.airborne) {
                runGroundPhysics(player, session);
            } else {
                runAirPhysics(player, session);
            }

            if (!sessions.containsKey(uuid)) {
                continue;
            }

            updateEffectsAndHud(player, session);
        }
    }

    private void updateThrottle(Player player, PlaneSession session) {
        if (player.isSneaking()) {
            session.throttle -= throttleDecreasePerTick;
        } else if (player.isSprinting()) {
            session.throttle += throttleIncreasePerTick;
        }
        session.throttle = clamp(session.throttle, 0.0, 1.0);
    }

    private void updateAirspeed(PlaneSession session, boolean braking) {
        double targetSpeed = session.throttle * maxAirspeed;
        double acceleration = session.airborne ? airAccelerationPerTick : groundAccelerationPerTick;

        if (session.airspeed < targetSpeed) {
            double availableAcceleration = acceleration * (0.30 + session.throttle * 0.70);
            session.airspeed += Math.min(targetSpeed - session.airspeed, availableAcceleration);
        } else if (session.airspeed > targetSpeed) {
            double aerodynamicDrag = dragPerTick * (1.0 + (session.airspeed / Math.max(maxAirspeed, 0.01)));
            session.airspeed -= Math.min(session.airspeed - targetSpeed, aerodynamicDrag);
        }

        if (session.airborne) {
            if (session.nose.getY() > 0.0) {
                session.airspeed -= climbDragPerTick * session.nose.getY();
            }
            session.airspeed -= bankDragPerTick * (Math.abs(session.bankDegrees) / Math.max(maxBankDegrees, 1.0));
        } else {
            session.airspeed -= rollingDragPerTick;
            if (braking) {
                session.airspeed -= groundBrakePerTick;
            }
        }

        session.airspeed = clamp(session.airspeed, 0.0, maxAirspeed);
    }

    private void runGroundPhysics(Player player, PlaneSession session) {
        Vector groundForward = session.nose.clone();
        groundForward.setY(0.0);
        if (groundForward.lengthSquared() < 0.0001) {
            groundForward = calculateNose(player, false);
        } else {
            groundForward.normalize();
        }
        session.nose = groundForward;

        Vector velocity = groundForward.clone().multiply(session.airspeed);
        velocity.setY(Math.min(player.getVelocity().getY(), 0.0));

        double pitchUp = getPitchUpDegrees(player);
        if (session.airspeed >= takeoffSpeed && pitchUp >= takeoffPitchDegrees) {
            session.airborne = true;
            session.nose = calculateNose(player, true);
            Vector takeoffVelocity = session.nose.clone().multiply(session.airspeed);
            takeoffVelocity.setY(Math.max(0.11, takeoffVelocity.getY() + 0.08));
            session.lastVelocity = takeoffVelocity.clone();
            player.setVelocity(takeoffVelocity);
            player.sendTitle(ChatColor.GREEN + "" + ChatColor.BOLD + "AIRBORNE",
                    ChatColor.AQUA + "Keep your airspeed above stall speed", 3, 25, 7);
            player.sendMessage(PREFIX + ChatColor.GREEN + "Liftoff! Pitch and yaw now steer the aircraft.");
            return;
        }

        if (!isNearGround(player, 1.10)) {
            session.airborne = true;
            session.nose = calculateNose(player, true);
            session.lastVelocity = velocity.clone();
            player.sendMessage(PREFIX + ChatColor.RED + "Runway ended before rotation speed — recover or expect a stall.");
            runAirPhysics(player, session);
            return;
        }

        if (session.collisionCooldown == 0 && detectCollision(player, velocity)) {
            handleCollision(player, session, velocity);
            return;
        }

        session.lastVelocity = velocity.clone();
        player.setVelocity(velocity);

        if (isOpenSpace(player.getLocation())) {
            session.lastSafeLocation = player.getLocation().clone();
        }
    }

    private void runAirPhysics(Player player, PlaneSession session) {
        double liftRatio = clamp(
                (session.airspeed - stallSpeed * 0.55)
                        / Math.max(cruiseSpeed - stallSpeed * 0.55, 0.01),
                0.0,
                1.0
        );

        double stallSeverity = session.airspeed >= stallSpeed
                ? 0.0
                : clamp((stallSpeed - session.airspeed) / Math.max(stallSpeed, 0.01), 0.0, 1.0);

        double bankFraction = Math.abs(session.bankDegrees) / Math.max(maxBankDegrees, 1.0);

        Vector velocity = session.nose.clone().multiply(session.airspeed);

        // Minecraft gravity is roughly 0.08 blocks/tick^2. At cruise speed the wing
        // supplies enough lift to counter most of it. Below stall speed, that support disappears.
        double liftSupport = gravityCompensation * liftRatio;
        double stallSink = stallSinkMax * stallSeverity;
        double bankSink = bankLiftPenalty * bankFraction;
        velocity.setY(velocity.getY() + liftSupport - stallSink - bankSink);

        if (player.isSneaking()
                && session.throttle <= landingMaxThrottle
                && session.airspeed <= landingMaxSpeed
                && Math.abs(getPitchUpDegrees(player)) <= landingMaxPitchDegrees
                && isNearGround(player, groundCheckDistance)) {
            finishFlight(player, session, true,
                    ChatColor.GREEN + "Touchdown! Plane Kit returned.");
            return;
        }

        if (session.collisionCooldown == 0 && detectCollision(player, velocity)) {
            handleCollision(player, session, velocity);
            return;
        }

        session.lastVelocity = velocity.clone();
        player.setVelocity(velocity);

        if (isOpenSpace(player.getLocation())) {
            session.lastSafeLocation = player.getLocation().clone();
        }
    }

    private boolean detectCollision(Player player, Vector velocity) {
        if (velocity.lengthSquared() < 0.0025) {
            return false;
        }

        double speed = velocity.length();
        double scanDistance = collisionScanBaseDistance + speed * collisionScanMovementMultiplier;
        Vector direction = velocity.clone().normalize();
        Location origin = player.getLocation().clone().add(0, 0.9, 0);

        RayTraceResult result = player.getWorld().rayTraceBlocks(
                origin,
                direction,
                scanDistance,
                FluidCollisionMode.NEVER,
                true
        );

        return result != null
                && result.getHitBlock() != null
                && result.getHitBlock().getType().isSolid();
    }

    private void handleCollision(Player player, PlaneSession session, Vector incomingVelocity) {
        double impactSpeed = session.airspeed;

        if (impactSpeed >= catastrophicAirspeed) {
            handleCrash(player, session,
                    String.format(Locale.US, "Catastrophic impact at %.2f airspeed", impactSpeed));
            return;
        }

        double normalizedSpeed = clamp(impactSpeed / Math.max(maxAirspeed, 0.01), 0.0, 1.0);
        double damage = collisionBaseDamage + collisionMaxSpeedDamage * normalizedSpeed * normalizedSpeed;

        session.health = Math.max(0.0, session.health - damage);
        session.airspeed *= 0.28;
        session.throttle *= 0.70;
        session.collisionCooldown = collisionCooldownTicks;

        Vector rebound = incomingVelocity.clone();
        if (rebound.lengthSquared() > 0.0001) {
            rebound.normalize().multiply(-0.18);
            rebound.setY(Math.max(0.08, rebound.getY()));
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

    private void updateEffectsAndHud(Player player, PlaneSession session) {
        if (tickCounter % 5 == 0) {
            int throttlePercent = (int) Math.round(session.throttle * 100.0);
            double pitchUp = getPitchUpDegrees(player);

            String status;
            NamedTextColor color;

            if (!session.airborne) {
                String rotation = session.airspeed >= takeoffSpeed
                        ? " | ROTATE: LOOK UP"
                        : String.format(Locale.US, " | V1 %.2f", takeoffSpeed);
                status = String.format(Locale.US,
                        "RUNWAY | THR %d%% | SPD %.2f%s",
                        throttlePercent, session.airspeed, rotation);
                color = session.airspeed >= takeoffSpeed ? NamedTextColor.GREEN : NamedTextColor.AQUA;
            } else {
                boolean stalled = session.airspeed < stallSpeed;
                status = String.format(Locale.US,
                        "%s | HP %.0f | THR %d%% | AIR %.2f | %s | PITCH %+.0f°",
                        stalled ? "STALL" : "FLIGHT",
                        session.health,
                        throttlePercent,
                        session.airspeed,
                        bankLabel(session.bankDegrees),
                        pitchUp);
                color = stalled
                        ? NamedTextColor.RED
                        : (session.health <= maxHealth * 0.30 ? NamedTextColor.GOLD : NamedTextColor.AQUA);
            }

            player.sendActionBar(Component.text(status, color));
        }

        if (particlesEnabled && tickCounter % 4 == 0 && session.airspeed > 0.15) {
            Vector back = session.nose.clone().multiply(-0.8);
            Location trail = player.getLocation().clone().add(0, 0.65, 0).add(back);
            player.getWorld().spawnParticle(Particle.CLOUD, trail, 2, 0.10, 0.08, 0.10, 0.005);
        }

        if (soundsEnabled && tickCounter % 14 == 0 && session.throttle > 0.05) {
            float pitch = (float) (0.70 + session.throttle * 0.80);
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_ELYTRA_FLYING, 0.30f, pitch);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_MINECART_RIDING, 0.18f, pitch);
        }
    }

    private Vector calculateNose(Player player, boolean airborne) {
        float yaw = player.getLocation().getYaw();
        double pitch = airborne
                ? clamp(player.getLocation().getPitch(), -maxPitchDegrees, maxPitchDegrees)
                : 0.0;

        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);

        Vector direction = new Vector(
                -Math.sin(yawRadians) * Math.cos(pitchRadians),
                -Math.sin(pitchRadians),
                Math.cos(yawRadians) * Math.cos(pitchRadians)
        );

        if (direction.lengthSquared() < 0.0001) {
            return new Vector(0, 0, 1);
        }
        return direction.normalize();
    }

    private Vector blendDirection(Vector current, Vector target, double response) {
        if (current == null || current.lengthSquared() < 0.0001) {
            return target.clone().normalize();
        }

        Vector blended = current.clone().multiply(1.0 - response)
                .add(target.clone().multiply(response));

        if (blended.lengthSquared() < 0.0001) {
            return target.clone().normalize();
        }
        return blended.normalize();
    }

    private double getPitchUpDegrees(Player player) {
        double clampedPitch = clamp(player.getLocation().getPitch(), -maxPitchDegrees, maxPitchDegrees);
        return -clampedPitch;
    }

    private String bankLabel(double bankDegrees) {
        if (Math.abs(bankDegrees) < 2.0) {
            return "BANK LEVEL";
        }
        return String.format(Locale.US, "BANK %s %.0f°",
                bankDegrees > 0.0 ? "R" : "L", Math.abs(bankDegrees));
    }

    private double wrapDegrees(double degrees) {
        double wrapped = degrees;
        while (wrapped > 180.0) {
            wrapped -= 360.0;
        }
        while (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    private void handleCrash(Player player, PlaneSession expectedSession, String reason) {
        PlaneSession session = sessions.remove(player.getUniqueId());
        if (session == null || session != expectedSession) {
            return;
        }

        Location crashLocation = player.getLocation().clone();
        restorePlayerState(player, session);

        World world = player.getWorld();
        if (soundsEnabled) {
            world.playSound(crashLocation, Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 0.65f);
        }
        if (particlesEnabled) {
            world.spawnParticle(Particle.EXPLOSION_EMITTER, crashLocation.clone().add(0, 0.8, 0), 1);
            world.spawnParticle(Particle.FLAME, crashLocation.clone().add(0, 0.8, 0),
                    35, 0.75, 0.75, 0.75, 0.08);
        }

        player.setVelocity(new Vector(0, 0.32, 0));
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

        restorePlayerState(player, session);
        player.setVelocity(new Vector());
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

    private void restorePlayerState(Player player, PlaneSession session) {
        try {
            player.setFlying(false);
            player.setFlySpeed(session.previousFlySpeed);
            player.setWalkSpeed(session.previousWalkSpeed);
            player.setAllowFlight(session.previousAllowFlight);
            if (session.previousAllowFlight && session.previousFlying) {
                player.setFlying(true);
            }
        } catch (IllegalArgumentException ignored) {
            player.setFlying(false);
            player.setAllowFlight(session.previousAllowFlight);
        }
    }

    private boolean isNearGround(Player player, double distance) {
        Location start = player.getLocation().clone().add(0, 0.15, 0);
        RayTraceResult result = player.getWorld().rayTraceBlocks(
                start,
                new Vector(0, -1, 0),
                distance,
                FluidCollisionMode.ALWAYS,
                true
        );
        return result != null
                && result.getHitBlock() != null
                && result.getHitBlock().getType().isSolid();
    }

    private boolean isOpenSpace(Location feet) {
        return feet.getBlock().isPassable()
                && feet.clone().add(0, 1, 0).getBlock().isPassable();
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
            sender.sendMessage(PREFIX + ChatColor.WHITE + "/plane status" + ChatColor.GRAY + " - show flight data");
            sender.sendMessage(PREFIX + ChatColor.WHITE + "/plane land" + ChatColor.GRAY + " - finish a safe landing");
            if (hasAdmin(sender)) {
                sender.sendMessage(PREFIX + ChatColor.WHITE + "/plane give [player]" + ChatColor.GRAY + " - give a Plane Kit");
                sender.sendMessage(PREFIX + ChatColor.WHITE + "/plane reload" + ChatColor.GRAY + " - reload flight tuning");
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
                            "%s: %s, %.0f/%.0f HP, %d%% throttle, %.2f airspeed, %s",
                            target.getName(),
                            session.airborne ? "AIRBORNE" : "RUNWAY",
                            session.health,
                            maxHealth,
                            (int) Math.round(session.throttle * 100.0),
                            session.airspeed,
                            bankLabel(session.bankDegrees)));
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

                if (!isNearGround(player, groundCheckDistance)) {
                    player.sendMessage(PREFIX + ChatColor.YELLOW + "Get closer to the ground first.");
                    return true;
                }

                if (session.airborne) {
                    if (session.airspeed > landingMaxSpeed) {
                        player.sendMessage(PREFIX + ChatColor.YELLOW + String.format(Locale.US,
                                "Too fast to land. Slow below %.2f airspeed.", landingMaxSpeed));
                        return true;
                    }
                    if (session.throttle > landingMaxThrottle) {
                        player.sendMessage(PREFIX + ChatColor.YELLOW + "Reduce throttle before landing.");
                        return true;
                    }
                    if (Math.abs(getPitchUpDegrees(player)) > landingMaxPitchDegrees) {
                        player.sendMessage(PREFIX + ChatColor.YELLOW + "Level the nose before landing.");
                        return true;
                    }
                }

                finishFlight(player, session, true, ChatColor.GREEN + "Landing complete. Plane Kit returned.");
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
        private final float previousWalkSpeed;

        private double health;
        private double throttle;
        private double airspeed;
        private boolean airborne;
        private Vector nose;
        private float lastYaw;
        private double bankDegrees;
        private int collisionCooldown;
        private Location lastSafeLocation;
        private Vector lastVelocity = new Vector();

        private PlaneSession(boolean previousAllowFlight,
                             boolean previousFlying,
                             float previousFlySpeed,
                             float previousWalkSpeed,
                             double health,
                             double throttle,
                             Vector nose,
                             float lastYaw,
                             Location lastSafeLocation) {
            this.previousAllowFlight = previousAllowFlight;
            this.previousFlying = previousFlying;
            this.previousFlySpeed = previousFlySpeed;
            this.previousWalkSpeed = previousWalkSpeed;
            this.health = health;
            this.throttle = throttle;
            this.nose = nose;
            this.lastYaw = lastYaw;
            this.lastSafeLocation = lastSafeLocation;
        }
    }
}
