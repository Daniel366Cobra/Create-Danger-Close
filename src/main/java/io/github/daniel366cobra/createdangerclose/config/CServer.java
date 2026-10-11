package io.github.daniel366cobra.createdangerclose.config;

/** Gameplay settings, per world and synced. Defaults are the intended balance. */
public class CServer extends CDCConfigBase {

    public final BattleDamage battleDamage = nested(0, BattleDamage::new,
            "Blast and shells hurting fire control equipment");
    public final Maintenance maintenance = nested(0, Maintenance::new,
            "Upkeep: dust on lenses, lubrication of mechanisms");
    public final FireControl fireControl = nested(0, FireControl::new,
            "Aiming and firing the guns");
    public final DangerClose dangerClose = nested(0, DangerClose::new,
            "Holding fire that would land on your own side until confirmed");
    public final Rangefinder rangefinder = nested(0, Rangefinder::new,
            "Rangefinder accuracy and reach");
    public final MapTable mapTable = nested(0, MapTable::new,
            "Map tables");

    @Override
    public String getName() {
        return "server";
    }

    public static class BattleDamage extends CDCConfigBase {
        public final ConfigBool allowBattleDamage = b(true, "allowBattleDamage",
                "Whether blast and hits jostle, break and crack equipment.",
                "Off: equipment can still be destroyed as blocks, but takes no other damage.");
        public final ConfigBool allowDirectHits = b(true, "allowDirectHits",
                "Whether shells, shrapnel and grapeshot passing through equipment break it.");
        public final ConfigDouble blastReach = d(3, 1, 8, "blastReach",
                "[in explosion radii]", "How far from an explosion equipment is jostled.");
        public final ConfigDouble breakDistance = d(0.9, 0, 8, "breakDistance",
                "[in explosion radii]", "Within this of an explosion, equipment breaks rather than jostles.");
        public final ConfigDouble lensCrackDistance = d(1.5, 0, 8, "lensCrackDistance",
                "[in explosion radii]", "Within this of an explosion, rangefinder lenses crack.");
        public final ConfigDouble maxJostleBias = d(1, 0, 5, "maxJostleBias",
                "[in degrees]", "How far a jostle can throw the aim off, at most.");

        @Override
        public String getName() {
            return "battleDamage";
        }
    }

    public static class Maintenance extends CDCConfigBase {
        public final Dust dust = nested(0, Dust::new, "Dust settling on rangefinder lenses");
        public final Lubrication lubrication = nested(0, Lubrication::new, "Mechanisms drying out and seizing");

        @Override
        public String getName() {
            return "maintenance";
        }
    }

    public static class Dust extends CDCConfigBase {
        public final ConfigBool allowDust = b(true, "allowDust",
                "Whether dust settles on rangefinder lenses, costing accuracy until washed off.");
        public final ConfigDouble daysToCake = d(3, 0.5, 100, "daysToCake",
                "[in days]", "How long a lens takes to cake over in the hottest, driest biome.",
                "Cooler and wetter biomes take longer; cold ones raise no dust.");
        public final ConfigDouble rainWashSeconds = d(30, 1, 600, "rainWashSeconds",
                "[in seconds]", "How long rain takes to wash a caked lens clean.");

        @Override
        public String getName() {
            return "dust";
        }
    }

    public static class Lubrication extends CDCConfigBase {
        public final ConfigBool allowWearAndSeizing = b(true, "allowWearAndSeizing",
                "Whether mechanisms dry out and, running dry, may seize until lubricated with honeycomb.");
        public final ConfigDouble daysDryAtFullSpeed = d(8, 0.5, 365, "daysDryAtFullSpeed",
                "[in days]", "How long a full mechanism takes to dry out at full speed (256 RPM).");
        public final ConfigInt lubricantPerBlock = i(250, 1, 10000, "lubricantPerBlock",
                "[in millibuckets]", "How much lubricating oil (spindle oil, lubrication oil) fills one block's lubrication",
                "from its reservoir.");
        public final ConfigInt plantOilPerBlock = i(500, 1, 10000, "plantOilPerBlock",
                "[in millibuckets]", "How much plant or seed oil fills one block's lubrication from its reservoir.");
        public final ConfigDouble daysDryAtRest = d(16, 0.5, 365, "daysDryAtRest",
                "[in days]", "How long it takes with its network at rest; in between, in proportion.");
        public final ConfigDouble seizeBelow = d(0.5, 0, 1, "seizeBelow",
                "[0 to 1]", "Below this much lubrication, a running mechanism may seize.");
        public final ConfigDouble seizesPerMinuteDry = d(0.1, 0, 10, "seizesPerMinuteDry",
                "[per minute]", "How often a bone-dry running mechanism seizes, on average.",
                "Less often the more lubrication is left, down to never at the threshold.");

        @Override
        public String getName() {
            return "lubrication";
        }
    }

    public static class FireControl extends CDCConfigBase {
        public final ConfigDouble onTargetTolerance = d(0.5, 0.05, 5, "onTargetTolerance",
                "[in degrees]", "How close to its solution a barrel must point to fire.");
        public final ConfigDouble salvoSpread = d(3, 0, 32, "salvoSpread",
                "[in blocks]", "How near the target a gun riding a turret laid for another gun",
                "(side by side on one cradle) must land to fire with the salvo.");
        public final ConfigDouble altitudeGate = d(16, 0, 512, "altitudeGate",
                "[in blocks]", "How far above or below a map or navigation table target a ship's guns",
                "may engage it without an Altitude Sensor.");
        public final ConfigInt maxServoRpm = i(32, 4, 256, "maxServoRpm",
                "[in RPM]", "The fastest a cannon servo drives; turrets traverse no faster than a mount at this speed.");
        public final ConfigInt minOperatingRpm = i(32, 1, 256, "minOperatingRpm",
                "[in RPM]", "How fast the battle network must turn for interfaces and servos to work.");

        @Override
        public String getName() {
            return "fireControl";
        }
    }

    public static class DangerClose extends CDCConfigBase {
        public final ConfigBool allowDangerClose = b(true, "allowDangerClose",
                "Whether a salvo that would land near your own guns, spotters or ships waits for a",
                "second press of the fire button to confirm it.");
        public final ConfigDouble confirmSeconds = d(5, 1, 30, "confirmSeconds",
                "[in seconds]", "How long after the warning a second press fires the salvo.");
        public final DangerZones zones = nested(0, DangerZones::new,
                "How far from where each kind of shell bursts is dangerous");

        @Override
        public String getName() {
            return "dangerClose";
        }
    }

    public static class DangerZones extends CDCConfigBase {
        public final ConfigDouble armorPiercingRadius = d(8, 0, 64, "armorPiercingRadius",
                "[in blocks]", "How far from where it bursts an armor-piercing shell is dangerous.");
        public final ConfigDouble flakRadius = d(6, 0, 64, "flakRadius",
                "[in blocks]", "How far from where it bursts a flak autocannon round is dangerous.");
        public final ConfigDouble fluidShellRadius = d(9, 0, 64, "fluidShellRadius",
                "[in blocks]", "How far from where it bursts a fluid shell is dangerous.");
        public final ConfigDouble highExplosiveRadius = d(13, 0, 64, "highExplosiveRadius",
                "[in blocks]", "How far from where it bursts a high-explosive shell (or mortar shell) is dangerous.");
        public final ConfigDouble otherShellRadius = d(3, 0, 64, "otherShellRadius",
                "[in blocks]", "How far from where it lands anything without a burst is dangerous",
                "(solid and armor-piercing shot, mortar stones, smoke, grapeshot).");
        public final ConfigDouble shrapnelConeAngle = d(30, 1, 90, "shrapnelConeAngle",
                "[in degrees]", "How wide the cone of balls a shrapnel shell throws forward is, either side of its path.");
        public final ConfigDouble shrapnelConeLength = d(32, 0, 256, "shrapnelConeLength",
                "[in blocks]", "How far forward of its burst a shrapnel shell's balls are dangerous.");

        @Override
        public String getName() {
            return "zones";
        }
    }

    public static class Rangefinder extends CDCConfigBase {
        public final ConfigInt maxRange = i(1024, 64, 4096, "maxRange",
                "[in blocks]", "The farthest a rangefinder can range.");
        public final ConfigDouble rangingError = d(0.44, 0.01, 10, "rangingError",
                "[in milliradians]", "How finely an operator can bring the images together:",
                "the range error grows with the square of the range, over the tube's baseline.");
        public final ConfigDouble crackedLensFactor = d(4, 1, 50, "crackedLensFactor",
                "How many times worse a cracked lens makes the ranging error (each lens).");
        public final ConfigDouble dustyLensFactor = d(2, 1, 20, "dustyLensFactor",
                "How many times worse a lens caked in dust makes the ranging error (each lens).");

        @Override
        public String getName() {
            return "rangefinder";
        }
    }

    public static class MapTable extends CDCConfigBase {
        public final ConfigBool allowChartingAroundFixes = b(true, "allowChartingAroundFixes",
                "Whether a connected map table charts the ground around where rangefinders aim.");
        public final ConfigInt rangefinderChartRadius = i(32, 8, 128, "rangefinderChartRadius",
                "[in blocks]", "How much ground around a rangefinder's fix is charted.");
        public final ConfigInt maxTableWidth = i(3, 1, 5, "maxTableWidth",
                "[in blocks]", "How wide a square neighboring map tables can join into.",
                "Larger tables already built split down when this is lowered.");
        public final ConfigInt maxSafeZones = i(8, 1, 32, "maxSafeZones",
                "How many safe zones one map table can hold.");

        @Override
        public String getName() {
            return "mapTable";
        }
    }
}