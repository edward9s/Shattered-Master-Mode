package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.effects.Speck;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.ElmoParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.FlameParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.LeafParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.SacrificialParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.ShaftParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.SnowParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.SparkParticle;
import com.shatteredpixel.shatteredpixeldungeon.effects.particles.WebParticle;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GooSprite;
import com.watabou.noosa.particles.Emitter;

public class ModEmitterHelper {

    public static int bind(Emitter emitter, String key) {
        if (key.equals("Fire")) {
            if (emitter != null) {
                emitter.pour(FlameParticle.FACTORY, 0.03f);
            }
            return 2;

        } else if (key.equals("Freezing")) {
            if (emitter != null) {
                emitter.start(SnowParticle.FACTORY, 0.05f, 0);
            }
            return 10;

        } else if (key.equals("Blizzard")) {
            if (emitter != null) {
                emitter.pour(Speck.factory(119, true), 0.4f);
            }
            return 120;

        } else if (key.equals("CorrosiveGas")) {
            if (emitter != null) {
                emitter.pour(Speck.factory(108), 0.4f);
            }
            return 25;

        } else if (key.equals("Electricity")) {
            if (emitter != null) {
                emitter.start(SparkParticle.FACTORY, 0.05f, 0);
            }
            return 20;

        } else if (key.equals("Alchemy")) {
            if (emitter != null) {
                emitter.start(Speck.factory(12), 0.33f, 0);
            }
            return 30;

        } else if (key.equals("ConfusionGas")) {
            if (emitter != null) {
                emitter.pour(Speck.factory(113, true), 0.4f);
            }
            return 800;

        } else if (key.equals("ParalyticGas")) {
            if (emitter != null) {
                emitter.pour(Speck.factory(109), 0.4f);
            }
            return 1000;

        } else if (key.equals("Inferno")) {
            if (emitter != null) {
                emitter.pour(Speck.factory(118, true), 0.4f);
            }
            return 120;

        } else if (key.equals("Regrowth")) {
            if (emitter != null) {
                emitter.start(LeafParticle.LEVEL_SPECIFIC, 0.2f, 0);
            }
            return 30;

        } else if (key.equals("GooWarn")) {
            if (emitter != null) {
                emitter.pour(GooSprite.GooParticle.FACTORY, 0.03f);
            }
            return 30;

        } else if (key.equals("Foliage")) {
            if (emitter != null) {
                emitter.start(ShaftParticle.FACTORY, 0.9f, 0);
            }
            return 30;

        } else if (key.equals("SmokeScreen")) {
            if (emitter != null) {
                emitter.pour(Speck.factory(116), 0.1f);
            }
            return 180;

        } else if (key.equals("SacrificialFire")) {
            if (emitter != null) {
                emitter.pour(SacrificialParticle.FACTORY, 0.05f);
            }
            return 30;

        } else if (key.equals("StenchGas")) {
            if (emitter != null) {
                emitter.pour(Speck.factory(111), 0.4f);
            }
            return 20;

        } else if (key.equals("StormCloud")) {
            if (emitter != null) {
                emitter.pour(Speck.factory(117), 0.4f);
            }
            return 120;

        } else if (key.equals("ToxicGas")) {
            if (emitter != null) {
                emitter.pour(Speck.factory(107), 0.4f);
            }
            return 1000;

        } else if (key.equals("VaultFlameTraps")) {
            if (emitter != null) {
                emitter.pour(ElmoParticle.FACTORY, 0.3f);
            }
            return 30;

        } else if (key.equals("WaterOfAwareness")) {
            if (emitter != null) {
                emitter.pour(Speck.factory(3), 0.3f);
            }
            return 30;

        } else if (key.equals("WaterOfHealth")) {
            if (emitter != null) {
                emitter.start(Speck.factory(0), 0.5f, 0);
            }
            return 30;

        } else if (key.equals("Web")) {
            if (emitter != null) {
                emitter.pour(WebParticle.FACTORY, 0.25f);
            }
            return 20;
        }

        return 100;
    }
}
