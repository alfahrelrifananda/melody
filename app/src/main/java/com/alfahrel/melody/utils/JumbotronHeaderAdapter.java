package com.alfahrel.melody.utils;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;

import java.util.Calendar;

/**
 * JumbotronHeaderAdapter
 * ──────────────────────
 * Single-item adapter rendering the Melody greeting jumbotron.
 * Blobs animate infinitely with no user controls — pure ambient vibe.
 *
 * Add as the FIRST adapter in ConcatAdapter (before pinnedStripHeaderAdapter):
 *
 *   jumbotronAdapter = new JumbotronHeaderAdapter();
 *
 *   binding.homeRecyclerView.setAdapter(new ConcatAdapter(
 *       jumbotronAdapter,           // ← first
 *       pinnedStripHeaderAdapter,
 *       songsAdapter,
 *       albumsAdapter,
 *       artistsAdapter
 *   ));
 */
public class JumbotronHeaderAdapter extends RecyclerView.Adapter<JumbotronHeaderAdapter.VH> {

    // ── Adapter ───────────────────────────────────────────────────────────────

    @Override public int getItemCount() { return 1; }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.jumbotron_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull VH holder) {
        super.onViewAttachedToWindow(holder);
        holder.startBlobs();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull VH holder) {
        super.onViewDetachedFromWindow(holder);
        holder.stopBlobs();
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {

        private final View     blob1, blob2, blob3;
        private final TextView greeting, tagline;
        private AnimatorSet    animSet;

        VH(@NonNull View v) {
            super(v);
            blob1    = v.findViewById(R.id.blob1);
            blob2    = v.findViewById(R.id.blob2);
            blob3    = v.findViewById(R.id.blob3);
            greeting = v.findViewById(R.id.jumbotronGreeting);
            tagline  = v.findViewById(R.id.jumbotronTagline);
        }

        void bind() {
            // ── Time-aware greeting ───────────────────────────────────────────
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

            String greetingText;
            String taglineText;

            if (hour >= 5 && hour < 12) {
                greetingText = "Good morning ";
                taglineText  = "Start your day with music";
            } else if (hour >= 12 && hour < 17) {
                greetingText = "Good afternoon";
                taglineText  = "Keep the vibe going";
            } else if (hour >= 17 && hour < 21) {
                greetingText = "Good evening";
                taglineText  = "Unwind with your favourites";
            } else {
                greetingText = "Still up?";
                taglineText  = "We've got late-night tunes";
            }

            greeting.setText(greetingText);
            tagline.setText(taglineText);
        }

        // ── Blob animation — infinite, no user interaction ────────────────────

        void startBlobs() {
            if (animSet != null && animSet.isRunning()) return;

            // Blob 1 — drifts diagonally right-down, pulses gently
            ObjectAnimator b1tx = tx(blob1,  0f,  52f, 7_200);
            ObjectAnimator b1ty = ty(blob1,  0f,  36f, 9_100);
            ObjectAnimator b1sx = sx(blob1, 1.00f, 1.20f, 11_300);
            ObjectAnimator b1sy = sy(blob1, 1.00f, 1.20f, 11_300);

            // Blob 2 — counter-drifts left-up, breathes larger
            ObjectAnimator b2tx = tx(blob2,  0f, -44f, 6_100);
            ObjectAnimator b2ty = ty(blob2,  0f, -30f, 8_400);
            ObjectAnimator b2sx = sx(blob2, 1.00f, 1.25f, 9_700);
            ObjectAnimator b2sy = sy(blob2, 1.00f, 1.25f, 9_700);

            // Blob 3 — floats up and down, slight sway
            ObjectAnimator b3tx = tx(blob3,  0f,  18f, 8_800);
            ObjectAnimator b3ty = ty(blob3,  0f, -24f, 5_600);
            ObjectAnimator b3sx = sx(blob3, 0.90f, 1.15f, 7_300);
            ObjectAnimator b3sy = sy(blob3, 0.90f, 1.15f, 7_300);

            animSet = new AnimatorSet();
            animSet.playTogether(
                    b1tx, b1ty, b1sx, b1sy,
                    b2tx, b2ty, b2sx, b2sy,
                    b3tx, b3ty, b3sx, b3sy);
            animSet.start();
        }

        void stopBlobs() {
            if (animSet != null) {
                animSet.cancel();
                animSet = null;
            }
        }

        // ── Animator helpers ──────────────────────────────────────────────────

        private static ObjectAnimator tx(View v, float from, float to, long ms) {
            return infinite(ObjectAnimator.ofFloat(v, "translationX", from, to), ms);
        }
        private static ObjectAnimator ty(View v, float from, float to, long ms) {
            return infinite(ObjectAnimator.ofFloat(v, "translationY", from, to), ms);
        }
        private static ObjectAnimator sx(View v, float from, float to, long ms) {
            return infinite(ObjectAnimator.ofFloat(v, "scaleX", from, to), ms);
        }
        private static ObjectAnimator sy(View v, float from, float to, long ms) {
            return infinite(ObjectAnimator.ofFloat(v, "scaleY", from, to), ms);
        }

        private static ObjectAnimator infinite(ObjectAnimator anim, long ms) {
            anim.setDuration(ms);
            anim.setRepeatMode(ValueAnimator.REVERSE);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new AccelerateDecelerateInterpolator());
            return anim;
        }
    }
}