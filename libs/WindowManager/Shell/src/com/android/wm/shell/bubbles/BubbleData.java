/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.wm.shell.bubbles;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;
import static com.android.wm.shell.bubbles.Bubble.KEY_APP_BUBBLE;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_DATA;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.app.PendingIntent;
import android.content.Context;
import android.content.LocusId;
import android.content.pm.ShortcutInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.launcher3.icons.BubbleIconFactory;
import com.android.wm.shell.R;
import com.android.wm.shell.bubbles.Bubbles.DismissReason;
import com.android.wm.shell.bubbles.bar.BubbleBarLayerView;
import com.android.wm.shell.common.bubbles.BubbleBarUpdate;
import com.android.wm.shell.common.bubbles.RemovedBubble;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Keeps track of active bubbles.
 */
public class BubbleData {

    private BubbleLogger mLogger;

    private int mCurrentUserId;

    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleData" : TAG_BUBBLES;

    private static final Comparator<Bubble> BUBBLES_BY_SORT_KEY_DESCENDING =
            Comparator.comparing(BubbleData::sortKey).reversed();

    /** Contains information about changes that have been made to the state of bubbles. */
    static final class Update {
        boolean expandedChanged;
        boolean selectionChanged;
        boolean orderChanged;
        boolean suppressedSummaryChanged;
        boolean expanded;
        boolean shouldShowEducation;
        @Nullable BubbleViewProvider selectedBubble;
        @Nullable Bubble addedBubble;
        @Nullable Bubble updatedBubble;
        @Nullable Bubble addedOverflowBubble;
        @Nullable Bubble removedOverflowBubble;
        @Nullable Bubble suppressedBubble;
        @Nullable Bubble unsuppressedBubble;
        @Nullable String suppressedSummaryGroup;
        // Pair with Bubble and @DismissReason Integer
        final List<Pair<Bubble, Integer>> removedBubbles = new ArrayList<>();

        // A read-only view of the bubbles list, changes there will be reflected here.
        final List<Bubble> bubbles;
        final List<Bubble> overflowBubbles;

        private Update(List<Bubble> row, List<Bubble> overflow) {
            bubbles = Collections.unmodifiableList(row);
            overflowBubbles = Collections.unmodifiableList(overflow);
        }

        boolean anythingChanged() {
            return expandedChanged
                    || selectionChanged
                    || addedBubble != null
                    || updatedBubble != null
                    || !removedBubbles.isEmpty()
                    || addedOverflowBubble != null
                    || removedOverflowBubble != null
                    || orderChanged
                    || suppressedBubble != null
                    || unsuppressedBubble != null
                    || suppressedSummaryChanged
                    || suppressedSummaryGroup != null;
        }

        void bubbleRemoved(Bubble bubbleToRemove, @DismissReason int reason) {
            removedBubbles.add(new Pair<>(bubbleToRemove, reason));
        }

        /**
         * Converts the update to a {@link BubbleBarUpdate} which contains updates relevant
         * to the bubble bar. Only used when {@link BubbleController#isShowingAsBubbleBar()} is
         * true.
         */
        BubbleBarUpdate toBubbleBarUpdate() {
            BubbleBarUpdate bubbleBarUpdate = new BubbleBarUpdate();

            bubbleBarUpdate.expandedChanged = expandedChanged;
            bubbleBarUpdate.expanded = expanded;
            bubbleBarUpdate.shouldShowEducation = shouldShowEducation;
            if (selectionChanged) {
                bubbleBarUpdate.selectedBubbleKey = selectedBubble != null
                        ? selectedBubble.getKey()
                        : null;
            }
            bubbleBarUpdate.addedBubble = addedBubble != null
                    ? addedBubble.asBubbleBarBubble()
                    : null;
            // TODO(b/269670235): We need to handle updates better, I think for the bubble bar only
            //  certain updates need to be sent instead of any updatedBubble.
            bubbleBarUpdate.updatedBubble = updatedBubble != null
                    ? updatedBubble.asBubbleBarBubble()
                    : null;
            bubbleBarUpdate.suppressedBubbleKey = suppressedBubble != null
                    ? suppressedBubble.getKey()
                    : null;
            bubbleBarUpdate.unsupressedBubbleKey = unsuppressedBubble != null
                    ? unsuppressedBubble.getKey()
                    : null;
            for (int i = 0; i < removedBubbles.size(); i++) {
                Pair<Bubble, Integer> pair = removedBubbles.get(i);
                bubbleBarUpdate.removedBubbles.add(
                        new RemovedBubble(pair.first.getKey(), pair.second));
            }
            if (orderChanged) {
                // Include the new order
                for (int i = 0; i < bubbles.size(); i++) {
                    bubbleBarUpdate.bubbleKeysInOrder.add(bubbles.get(i).getKey());
                }
            }
            return bubbleBarUpdate;
        }

        /**
         * Gets the current state of active bubbles and populates the update with that.  Only
         * used when {@link BubbleController#isShowingAsBubbleBar()} is true.
         */
        BubbleBarUpdate getInitialState() {
            BubbleBarUpdate bubbleBarUpdate = new BubbleBarUpdate();
            bubbleBarUpdate.shouldShowEducation = shouldShowEducation;
            for (int i = 0; i < bubbles.size(); i++) {
                bubbleBarUpdate.currentBubbleList.add(bubbles.get(i).asBubbleBarBubble());
            }
            return bubbleBarUpdate;
        }
    }

    /**
     * This interface reports changes to the state and appearance of bubbles which should be applied
     * as necessary to the UI.
     */
    interface Listener {
        /** Reports changes have have occurred as a result of the most recent operation. */
        void applyUpdate(Update update);
    }

    interface TimeSource {
        long currentTimeMillis();
    }

    private final Context mContext;
    private final BubblePositioner mPositioner;
    private final BubbleEducationController mEducationController;
    private final Executor mMainExecutor;
    /** Bubbles that are actively in the stack. */
    private final List<Bubble> mBubbles;
    /** Bubbles that aged out to overflow. */
    private final List<Bubble> mOverflowBubbles;
    /** Bubbles that are being loaded but haven't been added to the stack just yet. */
    private final HashMap<String, Bubble> mPendingBubbles;
    /** Bubbles that are suppressed due to locusId. */
    private final ArrayMap<LocusId, Bubble> mSuppressedBubbles = new ArrayMap<>();
    /** Visible locusIds. */
    private final ArraySet<LocusId> mVisibleLocusIds = new ArraySet<>();

    private BubbleViewProvider mSelectedBubble;
    private final BubbleOverflow mOverflow;
    private boolean mShowingOverflow;
    private boolean mExpanded;
    private int mMaxBubbles;
    private int mMaxOverflowBubbles;

    private boolean mNeedsTrimming;

    // State tracked during an operation -- keeps track of what listener events to dispatch.
    private Update mStateChange;

    private TimeSource mTimeSource = System::currentTimeMillis;

    @Nullable
    private Listener mListener;

    private Bubbles.BubbleMetadataFlagListener mBubbleMetadataFlagListener;
    private Bubbles.PendingIntentCanceledListener mCancelledListener;

    /**
     * We track groups with summaries that aren't visibly displayed but still kept around because
     * the bubble(s) associated with the summary still exist.
     *
     * The summary must be kept around so that developers can cancel it (and hence the bubbles
     * associated with it). This list is used to check if the summary should be hidden from the
     * shade.
     *
     * Key: group key of the notification
     * Value: key of the notification
     */
    private HashMap<String, String> mSuppressedGroupKeys = new HashMap<>();

    public BubbleData(Context context, BubbleLogger bubbleLogger, BubblePositioner positioner,
            BubbleEducationController educationController, Executor mainExecutor) {
        mContext = context;
        mLogger = bubbleLogger;
        mPositioner = positioner;
        mEducationController = educationController;
        mMainExecutor = mainExecutor;
        mOverflow = new BubbleOverflow(context, positioner);
        mBubbles = new ArrayList<>();
        mOverflowBubbles = new ArrayList<>();
        mPendingBubbles = new HashMap<>();
        mStateChange = new Update(mBubbles, mOverflowBubbles);
        mMaxBubbles = mPositioner.getMaxBubbles();
        mMaxOverflowBubbles = mContext.getResources().getInteger(R.integer.bubbles_max_overflow);
    }

    /**
     * Returns a bubble bar update populated with the current list of active bubbles.
     */
    public BubbleBarUpdate getInitialStateForBubbleBar() {
        return mStateChange.getInitialState();
    }

    public void setSuppressionChangedListener(Bubbles.BubbleMetadataFlagListener listener) {
        mBubbleMetadataFlagListener = listener;
    }

    public void setPendingIntentCancelledListener(
            Bubbles.PendingIntentCanceledListener listener) {
        mCancelledListener = listener;
    }

    public void onMaxBubblesChanged() {
        mMaxBubbles = mPositioner.getMaxBubbles();
        if (!mExpanded) {
            trim();
            dispatchPendingChanges();
        } else {
            mNeedsTrimming = true;
        }
    }

    public boolean hasBubbles() {
        return !mBubbles.isEmpty();
    }

    public boolean hasOverflowBubbles() {
        return !mOverflowBubbles.isEmpty();
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    public boolean hasAnyBubbleWithKey(String key) {
        return hasBubbleInStackWithKey(key) || hasOverflowBubbleWithKey(key)
                || hasSuppressedBubbleWithKey(key);
    }

    public boolean hasBubbleInStackWithKey(String key) {
        return getBubbleInStackWithKey(key) != null;
    }

    public boolean hasOverflowBubbleWithKey(String key) {
        return getOverflowBubbleWithKey(key) != null;
    }

    /**
     * Check if there are any bubbles suppressed with the given notification <code>key</code>
     */
    public boolean hasSuppressedBubbleWithKey(String key) {
        return mSuppressedBubbles.values().stream().anyMatch(b -> b.getKey().equals(key));
    }

    /**
     * Check if there are any bubbles suppressed with the given <code>LocusId</code>
     */
    public boolean isSuppressedWithLocusId(LocusId locusId) {
        return mSuppressedBubbles.get(locusId) != null;
    }

    @Nullable
    public BubbleViewProvider getSelectedBubble() {
        return mSelectedBubble;
    }

    public BubbleOverflow getOverflow() {
        return mOverflow;
    }

    /** Return a read-only current active bubble lists. */
    public List<Bubble> getActiveBubbles() {
        return Collections.unmodifiableList(mBubbles);
    }

    public void setExpanded(boolean expanded) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setExpanded: " + expanded);
        }
        setExpandedInternal(expanded);
        dispatchPendingChanges();
    }

    /**
     * Sets the selected bubble and expands it, but doesn't dispatch changes
     * to {@link BubbleData.Listener}. This is used for updates coming from launcher whose views
     * will already be updated so we don't need to notify them again, but BubbleData should be
     * updated to have the correct state.
     */
    public void setSelectedBubbleFromLauncher(BubbleViewProvider bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setSelectedBubbleFromLauncher: " + bubble);
        }
        mExpanded = true;
        if (Objects.equals(bubble, mSelectedBubble)) {
            return;
        }
        boolean isOverflow = bubble != null && BubbleOverflow.KEY.equals(bubble.getKey());
        if (bubble != null
                && !mBubbles.contains(bubble)
                && !mOverflowBubbles.contains(bubble)
                && !isOverflow) {
            Log.e(TAG, "Cannot select bubble which doesn't exist!"
                    + " (" + bubble + ") bubbles=" + mBubbles);
            return;
        }
        if (bubble != null && !isOverflow) {
            ((Bubble) bubble).markAsAccessedAt(mTimeSource.currentTimeMillis());
        }
        mSelectedBubble = bubble;
    }

    public void setSelectedBubble(BubbleViewProvider bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setSelectedBubble: " + bubble);
        }
        setSelectedBubbleInternal(bubble);
        dispatchPendingChanges();
    }

    void setShowingOverflow(boolean showingOverflow) {
        mShowingOverflow = showingOverflow;
    }

    boolean isShowingOverflow() {
        return mShowingOverflow && isExpanded();
    }

    /**
     * Constructs a new bubble or returns an existing one. Does not add new bubbles to
     * bubble data, must go through {@link #notificationEntryUpdated(Bubble, boolean, boolean)}
     * for that.
     *
     * @param entry The notification entry to use, only null if it's a bubble being promoted from
     *              the overflow that was persisted over reboot.
     * @param persistedBubble The bubble to use, only non-null if it's a bubble being promoted from
     *              the overflow that was persisted over reboot.
     */
    public Bubble getOrCreateBubble(BubbleEntry entry, Bubble persistedBubble) {
        String key = persistedBubble != null ? persistedBubble.getKey() : entry.getKey();
        Bubble bubbleToReturn = getBubbleInStackWithKey(key);

        if (bubbleToReturn == null) {
            bubbleToReturn = getOverflowBubbleWithKey(key);
            if (bubbleToReturn != null) {
                // Promoting from overflow
                mOverflowBubbles.remove(bubbleToReturn);
            } else if (mPendingBubbles.containsKey(key)) {
                // Update while it was pending
                bubbleToReturn = mPendingBubbles.get(key);
            } else if (entry != null) {
                // New bubble
                bubbleToReturn = new Bubble(entry, mBubbleMetadataFlagListener, mCancelledListener,
                        mMainExecutor);
            } else {
                // Persisted bubble being promoted
                bubbleToReturn = persistedBubble;
            }
        }

        if (entry != null) {
            bubbleToReturn.setEntry(entry);
        }
        mPendingBubbles.put(key, bubbleToReturn);
        return bubbleToReturn;
    }

    /**
     * When this method is called it is expected that all info in the bubble has completed loading.
     * @see Bubble#inflate(BubbleViewInfoTask.Callback, Context, BubbleController, BubbleStackView,
     * BubbleBarLayerView, BubbleIconFactory, boolean)
     */
    void notificationEntryUpdated(Bubble bubble, boolean suppressFlyout, boolean showInShade) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "notificationEntryUpdated: " + bubble);
        }
        mPendingBubbles.remove(bubble.getKey()); // No longer pending once we're here
        Bubble prevBubble = getBubbleInStackWithKey(bubble.getKey());
        suppressFlyout |= !bubble.isTextChanged();

        if (prevBubble == null) {
            // Create a new bubble
            bubble.setSuppressFlyout(suppressFlyout);
            bubble.markUpdatedAt(mTimeSource.currentTimeMillis());
            doAdd(bubble);
            trim();
        } else {
            // Updates an existing bubble
            bubble.setSuppressFlyout(suppressFlyout);
            // If there is no flyout, we probably shouldn't show the bubble at the top
            doUpdate(bubble, !suppressFlyout /* reorder */);
        }

        if (bubble.shouldAutoExpand()) {
            bubble.setShouldAutoExpand(false);
            setSelectedBubbleInternal(bubble);

            if (!mExpanded) {
                setExpandedInternal(true);
            }
        }

        boolean isBubbleExpandedAndSelected = mExpanded && mSelectedBubble == bubble;
        boolean suppress = isBubbleExpandedAndSelected || !showInShade || !bubble.showInShade();
        bubble.setSuppressNotification(suppress);
        bubble.setShowDot(!isBubbleExpandedAndSelected /* show */);

        LocusId locusId = bubble.getLocusId();
        if (locusId != null) {
            boolean isSuppressed = mSuppressedBubbles.containsKey(locusId);
            if (isSuppressed && (!bubble.isSuppressed() || !bubble.isSuppressable())) {
                mSuppressedBubbles.remove(locusId);
                doUnsuppress(bubble);
            } else if (!isSuppressed && (bubble.isSuppressed()
                    || bubble.isSuppressable() && mVisibleLocusIds.contains(locusId))) {
                mSuppressedBubbles.put(locusId, bubble);
                doSuppress(bubble);
            }
        }
        dispatchPendingChanges();
    }

    /**
     * Dismisses the bubble with the matching key, if it exists.
     */
    public void dismissBubbleWithKey(String key, @DismissReason int reason) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "notificationEntryRemoved: key=" + key + " reason=" + reason);
        }
        doRemove(key, reason);
        dispatchPendingChanges();
    }

    /**
     * Explicitly removes a bubble from the overflow, if it exists.
     *
     * @param bubble the bubble to remove.
     */
    public void removeOverflowBubble(Bubble bubble) {
        if (bubble == null) return;
        if (mOverflowBubbles.remove(bubble)) {
            mStateChange.removedOverflowBubble = bubble;
            dispatchPendingChanges();
        }
    }

    /**
     * Adds a group key indicating that the summary for this group should be suppressed.
     *
     * @param groupKey the group key of the group whose summary should be suppressed.
     * @param notifKey the notification entry key of that summary.
     */
    void addSummaryToSuppress(String groupKey, String notifKey) {
        mSuppressedGroupKeys.put(groupKey, notifKey);
        mStateChange.suppressedSummaryChanged = true;
        mStateChange.suppressedSummaryGroup = groupKey;
        dispatchPendingChanges();
    }

    /**
     * Retrieves the notif entry key of the summary associated with the provided group key.
     *
     * @param groupKey the group to look up
     * @return the key for the notification that is the summary of this group.
     */
    String getSummaryKey(String groupKey) {
        return mSuppressedGroupKeys.get(groupKey);
    }

    /**
     * Removes a group key indicating that summary for this group should no longer be suppressed.
     */
    void removeSuppressedSummary(String groupKey) {
        mSuppressedGroupKeys.remove(groupKey);
        mStateChange.suppressedSummaryChanged = true;
        mStateChange.suppressedSummaryGroup = groupKey;
        dispatchPendingChanges();
    }

    /**
     * Whether the summary for the provided group key is suppressed.
     */
    @VisibleForTesting
    public boolean isSummarySuppressed(String groupKey) {
        return mSuppressedGroupKeys.containsKey(groupKey);
    }

    /**
     * Removes bubbles from the given package whose shortcut are not in the provided list of valid
     * shortcuts.
     */
    public void removeBubblesWithInvalidShortcuts(
            String packageName, List<ShortcutInfo> validShortcuts, int reason) {

        final Set<String> validShortcutIds = new HashSet<String>();
        for (ShortcutInfo info : validShortcuts) {
            validShortcutIds.add(info.getId());
        }

        final Predicate<Bubble> invalidBubblesFromPackage = bubble -> {
            final boolean bubbleIsFromPackage = packageName.equals(bubble.getPackageName());
            final boolean isShortcutBubble = bubble.hasMetadataShortcutId();
            if (!bubbleIsFromPackage || !isShortcutBubble) {
                return false;
            }
            final boolean hasShortcutIdAndValidShortcut =
                    bubble.hasMetadataShortcutId()
                            && bubble.getShortcutInfo() != null
                            && bubble.getShortcutInfo().isEnabled()
                            && validShortcutIds.contains(bubble.getShortcutInfo().getId());
            return bubbleIsFromPackage && !hasShortcutIdAndValidShortcut;
        };

        final Consumer<Bubble> removeBubble = bubble ->
                dismissBubbleWithKey(bubble.getKey(), reason);

        performActionOnBubblesMatching(getBubbles(), invalidBubblesFromPackage, removeBubble);
        performActionOnBubblesMatching(
                getOverflowBubbles(), invalidBubblesFromPackage, removeBubble);
    }

    /** Removes all bubbles from the given package. */
    public void removeBubblesWithPackageName(String packageName, int reason) {
        final Predicate<Bubble> bubbleMatchesPackage = bubble ->
                bubble.getPackageName().equals(packageName);

        final Consumer<Bubble> removeBubble = bubble ->
                dismissBubbleWithKey(bubble.getKey(), reason);

        performActionOnBubblesMatching(getBubbles(), bubbleMatchesPackage, removeBubble);
        performActionOnBubblesMatching(getOverflowBubbles(), bubbleMatchesPackage, removeBubble);
    }

    /** Removes all bubbles for the given user. */
    public void removeBubblesForUser(int userId) {
        List<Bubble> removedBubbles = filterAllBubbles(bubble ->
                userId == bubble.getUser().getIdentifier());
        for (Bubble b : removedBubbles) {
            doRemove(b.getKey(), Bubbles.DISMISS_USER_REMOVED);
        }
        if (!removedBubbles.isEmpty()) {
            dispatchPendingChanges();
        }
    }

    private void doAdd(Bubble bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "doAdd: " + bubble);
        }
        mBubbles.add(0, bubble);
        mStateChange.addedBubble = bubble;
        // Adding the first bubble doesn't change the order
        mStateChange.orderChanged = mBubbles.size() > 1;
        if (!isExpanded()) {
            setSelectedBubbleInternal(mBubbles.get(0));
        }
    }

    private void trim() {
        if (mBubbles.size() > mMaxBubbles) {
            int numtoRemove = mBubbles.size() - mMaxBubbles;
            ArrayList<Bubble> toRemove = new ArrayList<>();
            mBubbles.stream()
                    // sort oldest first (ascending lastActivity)
                    .sorted(Comparator.comparingLong(Bubble::getLastActivity))
                    // skip the selected bubble
                    .filter((b) -> !b.equals(mSelectedBubble))
                    .forEachOrdered((b) -> {
                        if (toRemove.size() < numtoRemove) {
                            toRemove.add(b);
                        }
                    });
            toRemove.forEach((b) -> doRemove(b.getKey(), Bubbles.DISMISS_AGED));
        }
    }

    private void doUpdate(Bubble bubble, boolean reorder) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "doUpdate: " + bubble);
        }
        mStateChange.updatedBubble = bubble;
        if (!isExpanded() && reorder) {
            int prevPos = mBubbles.indexOf(bubble);
            mBubbles.remove(bubble);
            mBubbles.add(0, bubble);
            mStateChange.orderChanged = prevPos != 0;
            setSelectedBubbleInternal(mBubbles.get(0));
        }
    }

    /** Runs the given action on Bubbles that match the given predicate. */
    private void performActionOnBubblesMatching(
            List<Bubble> bubbles, Predicate<Bubble> predicate, Consumer<Bubble> action) {
        final List<Bubble> matchingBubbles = new ArrayList<>();
        for (Bubble bubble : bubbles) {
            if (predicate.test(bubble)) {
                matchingBubbles.add(bubble);
            }
        }

        for (Bubble matchingBubble : matchingBubbles) {
            action.accept(matchingBubble);
        }
    }

    private void doRemove(String key, @DismissReason int reason) {
        if (DEBUG_BUBBLE_DATA || (key != null && key.contains(KEY_APP_BUBBLE))) {
            Log.d(TAG, "doRemove: " + key + " reason: " + reason);
        }
        //  If it was pending remove it
        if (mPendingBubbles.containsKey(key)) {
            mPendingBubbles.remove(key);
        }

        boolean shouldRemoveHiddenBubble = reason == Bubbles.DISMISS_NOTIF_CANCEL
                || reason == Bubbles.DISMISS_GROUP_CANCELLED
                || reason == Bubbles.DISMISS_NO_LONGER_BUBBLE
                || reason == Bubbles.DISMISS_BLOCKED
                || reason == Bubbles.DISMISS_SHORTCUT_REMOVED
                || reason == Bubbles.DISMISS_PACKAGE_REMOVED
                || reason == Bubbles.DISMISS_USER_CHANGED
                || reason == Bubbles.DISMISS_USER_REMOVED;

        int indexToRemove = indexForKey(key);
        if (indexToRemove == -1) {
            if (hasOverflowBubbleWithKey(key)
                    && shouldRemoveHiddenBubble) {

                Bubble b = getOverflowBubbleWithKey(key);
                if (DEBUG_BUBBLE_DATA) {
                    Log.d(TAG, "Cancel overflow bubble: " + b);
                }
                if (b != null) {
                    b.stopInflation();
                }
                mLogger.logOverflowRemove(b, reason);
                mOverflowBubbles.remove(b);
                mStateChange.bubbleRemoved(b, reason);
                mStateChange.removedOverflowBubble = b;
            }
            if (hasSuppressedBubbleWithKey(key) && shouldRemoveHiddenBubble) {
                Bubble b = getSuppressedBubbleWithKey(key);
                if (DEBUG_BUBBLE_DATA) {
                    Log.d(TAG, "Cancel suppressed bubble: " + b);
                }
                if (b != null) {
                    mSuppressedBubbles.remove(b.getLocusId());
                    b.stopInflation();
                    mStateChange.bubbleRemoved(b, reason);
                }
            }
            return;
        }
        Bubble bubbleToRemove = mBubbles.get(indexToRemove);
        bubbleToRemove.stopInflation();
        overflowBubble(reason, bubbleToRemove);

        if (mBubbles.size() == 1) {
            setExpandedInternal(false);
            // Don't use setSelectedBubbleInternal because we don't want to trigger an
            // applyUpdate
            mSelectedBubble = null;
        }
        if (indexToRemove < mBubbles.size() - 1) {
            // Removing anything but the last bubble means positions will change.
            mStateChange.orderChanged = true;
        }
        mBubbles.remove(indexToRemove);
        mStateChange.bubbleRemoved(bubbleToRemove, reason);
        if (!isExpanded()) {
            mStateChange.orderChanged |= repackAll();
        }

        // Note: If mBubbles.isEmpty(), then mSelectedBubble is now null.
        if (Objects.equals(mSelectedBubble, bubbleToRemove)) {
            setNewSelectedIndex(indexToRemove);
        }
        maybeSendDeleteIntent(reason, bubbleToRemove);
    }

    private void setNewSelectedIndex(int indexOfSelected) {
        if (mBubbles.isEmpty()) {
            Log.w(TAG, "Bubbles list empty when attempting to select index: " + indexOfSelected);
            return;
        }
        // Move selection to the new bubble at the same position.
        int newIndex = Math.min(indexOfSelected, mBubbles.size() - 1);
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setNewSelectedIndex: " + indexOfSelected);
        }
        BubbleViewProvider newSelected = mBubbles.get(newIndex);
        setSelectedBubbleInternal(newSelected);
    }

    private void doSuppress(Bubble bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "doSuppressed: " + bubble);
        }
        mStateChange.suppressedBubble = bubble;
        bubble.setSuppressBubble(true);

        int indexToRemove = mBubbles.indexOf(bubble);
        // Order changes if we are not suppressing the last bubble
        mStateChange.orderChanged = !(mBubbles.size() - 1 == indexToRemove);
        mBubbles.remove(indexToRemove);

        // Update selection if we suppressed the selected bubble
        if (Objects.equals(mSelectedBubble, bubble)) {
            if (mBubbles.isEmpty()) {
                // Don't use setSelectedBubbleInternal because we don't want to trigger an
                // applyUpdate
                mSelectedBubble = null;
            } else {
                // Mark new first bubble as selected
                setNewSelectedIndex(0);
            }
        }
    }

    private void doUnsuppress(Bubble bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "doUnsuppressed: " + bubble);
        }
        bubble.setSuppressBubble(false);
        mStateChange.unsuppressedBubble = bubble;
        mBubbles.add(bubble);
        if (mBubbles.size() > 1) {
            // See where the bubble actually lands
            repackAll();
            mStateChange.orderChanged = true;
        }
        if (mBubbles.get(0) == bubble) {
            // Unsuppressed bubble is sorted to first position. Mark it as the selected.
            setNewSelectedIndex(0);
        }
    }

    void overflowBubble(@DismissReason int reason, Bubble bubble) {
        if (bubble.getPendingIntentCanceled()
                || !(reason == Bubbles.DISMISS_AGED
                || reason == Bubbles.DISMISS_USER_GESTURE
                || reason == Bubbles.DISMISS_RELOAD_FROM_DISK)) {
            return;
        }
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "Overflowing: " + bubble);
        }
        mLogger.logOverflowAdd(bubble, reason);
        mOverflowBubbles.remove(bubble);
        mOverflowBubbles.add(0, bubble);
        mStateChange.addedOverflowBubble = bubble;
        bubble.stopInflation();
        if (mOverflowBubbles.size() == mMaxOverflowBubbles + 1) {
            // Remove oldest bubble.
            Bubble oldest = mOverflowBubbles.get(mOverflowBubbles.size() - 1);
            if (DEBUG_BUBBLE_DATA) {
                Log.d(TAG, "Overflow full. Remove: " + oldest);
            }
            mStateChange.bubbleRemoved(oldest, Bubbles.DISMISS_OVERFLOW_MAX_REACHED);
            mLogger.log(bubble, BubbleLogger.Event.BUBBLE_OVERFLOW_REMOVE_MAX_REACHED);
            mOverflowBubbles.remove(oldest);
            mStateChange.removedOverflowBubble = oldest;
        }
    }

    public void dismissAll(@DismissReason int reason) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "dismissAll: reason=" + reason);
        }
        if (mBubbles.isEmpty() && mSuppressedBubbles.isEmpty()) {
            return;
        }
        setExpandedInternal(false);
        setSelectedBubbleInternal(null);
        while (!mBubbles.isEmpty()) {
            doRemove(mBubbles.get(0).getKey(), reason);
        }
        while (!mSuppressedBubbles.isEmpty()) {
            Bubble bubble = mSuppressedBubbles.removeAt(0);
            doRemove(bubble.getKey(), reason);
        }
        dispatchPendingChanges();
    }

    /**
     * Called in response to the visibility of a locusId changing. A locusId is set on a task
     * and if there's a matching bubble for that locusId then the bubble may be hidden or shown
     * depending on the visibility of the locusId.
     *
     * @param taskId  the taskId associated with the locusId visibility change.
     * @param locusId the locusId whose visibility has changed.
     * @param visible whether the task with the locusId is visible or not.
     */
    public void onLocusVisibilityChanged(int taskId, LocusId locusId, boolean visible) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "onLocusVisibilityChanged: " + locusId + " visible=" + visible);
        }

        Bubble matchingBubble = getBubbleInStackWithLocusId(locusId);
        // Don't add the locus if it's from a bubble'd activity, we only suppress for non-bubbled.
        if (visible && (matchingBubble == null || matchingBubble.getTaskId() != taskId)) {
            mVisibleLocusIds.add(locusId);
        } else {
            mVisibleLocusIds.remove(locusId);
        }
        if (matchingBubble == null) {
            // Check if there is a suppressed bubble for this LocusId
            matchingBubble = mSuppressedBubbles.get(locusId);
            if (matchingBubble == null) {
                return;
            }
        }
        boolean isAlreadySuppressed = mSuppressedBubbles.get(locusId) != null;
        if (visible && !isAlreadySuppressed && matchingBubble.isSuppressable()
                && taskId != matchingBubble.getTaskId()) {
            mSuppressedBubbles.put(locusId, matchingBubble);
            doSuppress(matchingBubble);
            dispatchPendingChanges();
        } else if (!visible) {
            Bubble unsuppressedBubble = mSuppressedBubbles.remove(locusId);
            if (unsuppressedBubble != null) {
                doUnsuppress(unsuppressedBubble);
            }
            dispatchPendingChanges();
        }
    }

    /**
     * Removes all bubbles from the overflow, called when the user changes.
     */
    public void clearOverflow() {
        while (!mOverflowBubbles.isEmpty()) {
            doRemove(mOverflowBubbles.get(0).getKey(), Bubbles.DISMISS_USER_CHANGED);
        }
        dispatchPendingChanges();
    }

    private void dispatchPendingChanges() {
        if (mListener != null && mStateChange.anythingChanged()) {
            mStateChange.shouldShowEducation = mSelectedBubble != null
                    && mEducationController.shouldShowStackEducation(mSelectedBubble)
                    && !mExpanded;
            mListener.applyUpdate(mStateChange);
        }
        mStateChange = new Update(mBubbles, mOverflowBubbles);
    }

    /**
     * Requests a change to the selected bubble.
     *
     * @param bubble the new selected bubble
     */
    private void setSelectedBubbleInternal(@Nullable BubbleViewProvider bubble) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setSelectedBubbleInternal: " + bubble);
        }
        if (Objects.equals(bubble, mSelectedBubble)) {
            return;
        }
        boolean isOverflow = bubble != null && BubbleOverflow.KEY.equals(bubble.getKey());
        if (bubble != null
                && !mBubbles.contains(bubble)
                && !mOverflowBubbles.contains(bubble)
                && !isOverflow) {
            Log.e(TAG, "Cannot select bubble which doesn't exist!"
                    + " (" + bubble + ") bubbles=" + mBubbles);
            return;
        }
        if (mExpanded && bubble != null && !isOverflow) {
            ((Bubble) bubble).markAsAccessedAt(mTimeSource.currentTimeMillis());
        }
        mSelectedBubble = bubble;
        mStateChange.selectedBubble = bubble;
        mStateChange.selectionChanged = true;
    }

    void setCurrentUserId(int uid) {
        mCurrentUserId = uid;
    }

    /**
     * Logs the bubble UI event.
     *
     * @param provider    The bubble view provider that is being interacted on. Null value indicates
     *                    that the user interaction is not specific to one bubble.
     * @param action      The user interaction enum
     * @param packageName SystemUI package
     * @param bubbleCount Number of bubbles in the stack
     * @param bubbleIndex Index of bubble in the stack
     * @param normalX     Normalized x position of the stack
     * @param normalY     Normalized y position of the stack
     */
    void logBubbleEvent(@Nullable BubbleViewProvider provider, int action, String packageName,
            int bubbleCount, int bubbleIndex, float normalX, float normalY) {
        if (provider == null) {
            mLogger.logStackUiChanged(packageName, action, bubbleCount, normalX, normalY);
        } else if (provider.getKey().equals(BubbleOverflow.KEY)) {
            if (action == FrameworkStatsLog.BUBBLE_UICHANGED__ACTION__EXPANDED) {
                mLogger.logShowOverflow(packageName, mCurrentUserId);
            }
        } else {
            mLogger.logBubbleUiChanged((Bubble) provider, packageName, action, bubbleCount, normalX,
                    normalY, bubbleIndex);
        }
    }

    /**
     * Requests a change to the expanded state.
     *
     * @param shouldExpand the new requested state
     */
    private void setExpandedInternal(boolean shouldExpand) {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "setExpandedInternal: shouldExpand=" + shouldExpand);
        }
        if (mExpanded == shouldExpand) {
            return;
        }
        if (shouldExpand) {
            if (mBubbles.isEmpty() && !mShowingOverflow) {
                Log.e(TAG, "Attempt to expand stack when empty!");
                return;
            }
            if (mSelectedBubble == null) {
                Log.e(TAG, "Attempt to expand stack without selected bubble!");
                return;
            }
            if (mSelectedBubble.getKey().equals(mOverflow.getKey()) && !mBubbles.isEmpty()) {
                // Show previously selected bubble instead of overflow menu when expanding.
                setSelectedBubbleInternal(mBubbles.get(0));
            }
            if (mSelectedBubble instanceof Bubble) {
                ((Bubble) mSelectedBubble).markAsAccessedAt(mTimeSource.currentTimeMillis());
            }
            mStateChange.orderChanged |= repackAll();
        } else if (!mBubbles.isEmpty()) {
            // Apply ordering and grouping rules from expanded -> collapsed, then save
            // the result.
            mStateChange.orderChanged |= repackAll();
            if (mBubbles.indexOf(mSelectedBubble) > 0) {
                // Move the selected bubble to the top while collapsed.
                int index = mBubbles.indexOf(mSelectedBubble);
                if (index != 0) {
                    mBubbles.remove((Bubble) mSelectedBubble);
                    mBubbles.add(0, (Bubble) mSelectedBubble);
                    mStateChange.orderChanged = true;
                }
            }
        }
        if (mNeedsTrimming) {
            mNeedsTrimming = false;
            trim();
        }
        mExpanded = shouldExpand;
        mStateChange.expanded = shouldExpand;
        mStateChange.expandedChanged = true;
    }

    private static long sortKey(Bubble bubble) {
        return bubble.getLastActivity();
    }

    /**
     * This applies a full sort and group pass to all existing bubbles.
     * Bubbles are sorted by lastUpdated descending.
     *
     * @return true if the position of any bubbles changed as a result
     */
    private boolean repackAll() {
        if (DEBUG_BUBBLE_DATA) {
            Log.d(TAG, "repackAll()");
        }
        if (mBubbles.isEmpty()) {
            return false;
        }
        List<Bubble> repacked = new ArrayList<>(mBubbles.size());
        // Add bubbles, freshest to oldest
        mBubbles.stream()
                .sorted(BUBBLES_BY_SORT_KEY_DESCENDING)
                .forEachOrdered(repacked::add);
        if (repacked.equals(mBubbles)) {
            return false;
        }
        mBubbles.clear();
        mBubbles.addAll(repacked);
        return true;
    }

    private void maybeSendDeleteIntent(@DismissReason int reason, @NonNull final Bubble bubble) {
        if (reason != Bubbles.DISMISS_USER_GESTURE) return;
        PendingIntent deleteIntent = bubble.getDeleteIntent();
        if (deleteIntent == null) return;
        try {
            deleteIntent.send();
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, "Failed to send delete intent for bubble with key: " + bubble.getKey());
        }
    }

    private int indexForKey(String key) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The set of bubbles in row.
     */
    public List<Bubble> getBubbles() {
        return Collections.unmodifiableList(mBubbles);
    }

    /**
     * The set of bubbles in overflow.
     */
    @VisibleForTesting(visibility = PRIVATE)
    public List<Bubble> getOverflowBubbles() {
        return Collections.unmodifiableList(mOverflowBubbles);
    }

    @VisibleForTesting(visibility = PRIVATE)
    @Nullable
    Bubble getAnyBubbleWithkey(String key) {
        Bubble b = getBubbleInStackWithKey(key);
        if (b == null) {
            b = getOverflowBubbleWithKey(key);
        }
        if (b == null) {
            b = getSuppressedBubbleWithKey(key);
        }
        return b;
    }

    /** @return any bubble (in the stack or the overflow) that matches the provided shortcutId. */
    @Nullable
    Bubble getAnyBubbleWithShortcutId(String shortcutId) {
        if (TextUtils.isEmpty(shortcutId)) {
            return null;
        }
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            String bubbleShortcutId = bubble.getShortcutInfo() != null
                    ? bubble.getShortcutInfo().getId()
                    : bubble.getMetadataShortcutId();
            if (shortcutId.equals(bubbleShortcutId)) {
                return bubble;
            }
        }

        for (int i = 0; i < mOverflowBubbles.size(); i++) {
            Bubble bubble = mOverflowBubbles.get(i);
            String bubbleShortcutId = bubble.getShortcutInfo() != null
                    ? bubble.getShortcutInfo().getId()
                    : bubble.getMetadataShortcutId();
            if (shortcutId.equals(bubbleShortcutId)) {
                return bubble;
            }
        }
        return null;
    }

    @VisibleForTesting(visibility = PRIVATE)
    @Nullable
    public Bubble getBubbleInStackWithKey(String key) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return bubble;
            }
        }
        return null;
    }

    @Nullable
    private Bubble getBubbleInStackWithLocusId(LocusId locusId) {
        if (locusId == null) return null;
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (locusId.equals(bubble.getLocusId())) {
                return bubble;
            }
        }
        return null;
    }

    @Nullable
    Bubble getBubbleWithView(View view) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getIconView() != null && bubble.getIconView().equals(view)) {
                return bubble;
            }
        }
        return null;
    }

    public Bubble getOverflowBubbleWithKey(String key) {
        for (int i = 0; i < mOverflowBubbles.size(); i++) {
            Bubble bubble = mOverflowBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return bubble;
            }
        }
        return null;
    }

    /**
     * Get a suppressed bubble with given notification <code>key</code>
     *
     * @param key notification key
     * @return bubble that matches or null
     */
    @Nullable
    @VisibleForTesting(visibility = PRIVATE)
    public Bubble getSuppressedBubbleWithKey(String key) {
        for (Bubble b : mSuppressedBubbles.values()) {
            if (b.getKey().equals(key)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Get a pending bubble with given notification <code>key</code>
     *
     * @param key notification key
     * @return bubble that matches or null
     */
    @VisibleForTesting(visibility = PRIVATE)
    public Bubble getPendingBubbleWithKey(String key) {
        for (Bubble b : mPendingBubbles.values()) {
            if (b.getKey().equals(key)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Returns a list of bubbles that match the provided predicate. This checks all types of
     * bubbles (i.e. pending, suppressed, active, and overflowed).
     */
    private List<Bubble> filterAllBubbles(Predicate<Bubble> predicate) {
        ArrayList<Bubble> matchingBubbles = new ArrayList<>();
        for (Bubble b : mPendingBubbles.values()) {
            if (predicate.test(b)) {
                matchingBubbles.add(b);
            }
        }
        for (Bubble b : mSuppressedBubbles.values()) {
            if (predicate.test(b)) {
                matchingBubbles.add(b);
            }
        }
        for (Bubble b : mBubbles) {
            if (predicate.test(b)) {
                matchingBubbles.add(b);
            }
        }
        for (Bubble b : mOverflowBubbles) {
            if (predicate.test(b)) {
                matchingBubbles.add(b);
            }
        }
        return matchingBubbles;
    }

    @VisibleForTesting(visibility = PRIVATE)
    void setTimeSource(TimeSource timeSource) {
        mTimeSource = timeSource;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Set maximum number of bubbles allowed in overflow.
     * This method should only be used in tests, not in production.
     */
    @VisibleForTesting
    public void setMaxOverflowBubbles(int maxOverflowBubbles) {
        mMaxOverflowBubbles = maxOverflowBubbles;
    }

    /**
     * Description of current bubble data state.
     */
    public void dump(PrintWriter pw) {
        pw.println("BubbleData state:");
        pw.print("  selected: ");
        pw.println(mSelectedBubble != null
                ? mSelectedBubble.getKey()
                : "null");
        pw.print("  expanded: ");
        pw.println(mExpanded);

        pw.print("Stack bubble count: ");
        pw.println(mBubbles.size());
        for (Bubble bubble : mBubbles) {
            bubble.dump(pw);
        }

        pw.print("Overflow bubble count: ");
        pw.println(mOverflowBubbles.size());
        for (Bubble bubble : mOverflowBubbles) {
            bubble.dump(pw);
        }

        pw.print("SummaryKeys: ");
        pw.println(mSuppressedGroupKeys.size());
        for (String key : mSuppressedGroupKeys.keySet()) {
            pw.println("     suppressing: " + key);
        }
    }
}
