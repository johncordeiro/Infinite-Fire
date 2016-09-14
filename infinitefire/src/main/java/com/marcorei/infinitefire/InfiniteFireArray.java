/* (c) 2015 Markus Riegel
 * license: MIT
 */
package com.marcorei.infinitefire;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * This class implements an array-like collection based on a {@link Query}.
 * It supports {@link #reset() pull-to-refresh} and {@link #more() load-more}.
 * It also dispatches loading events for the initial loading procedure and when using {@link #more()} or {@link #reset()}.
 * It returns generically typed {@link InfiniteFireSnapshot}s so you can replace it for testing.
 */
public class InfiniteFireArray<T> {

    /**
     * This Event is dispatched when data in the array changes.
     */
    public interface OnChangedListener {
        enum EventType {Added, Changed, Removed, Moved, Reset}
        void onChanged(EventType type, int index, int oldIndex);
    }

    /**
     * Observe loading state changes.
     * Please note that Firebase is real-time and loading will never finish until the array is cleared, even if EventType.Done is dispatched.
     * The events dispatched here are only for the initial sync-process and when using {@link #more() more()} or {@link #reset() reset()}.
     */
    public interface OnLoadingStatusListener {
        enum EventType {LoadingNoContent, LoadingContent, Done}
        void onChanged(EventType type);
    }

    /**
     * This event is dispatched in case of a Firebase error.
     * If this event is dispatched the InfiniteFireArray will be cleared.
     */
    public interface OnFirebaseErrorListener {
        void onFirebaseError(DatabaseError firebaseError);
    }

    private Class<T> ItemClass;
    private Query originalQuery;
    private Query currentQuery;
    private ChildEventListener childEventListener;
    private ValueEventListener valueEventListener;
    private ArrayList<DataSnapshot> dataSnapshots = new ArrayList<DataSnapshot>();

    private int initialSize;
    private int pageSize;
    private boolean limitToFirst;
    private boolean fixedItemPositions;
    private int currentLimit;
    private boolean loading = false;
    private boolean refreshing = false;
    private boolean endOfDataReached = false;
    private boolean eraseOnData = false;

    private OnChangedListener onChangedListener;
    private OnFirebaseErrorListener onFirebaseErrorListener;
    private ArrayList<OnLoadingStatusListener> onLoadingStatusListeners = new ArrayList<OnLoadingStatusListener>();

    /**
     * @param query Firebase reference oder Query. You can order the Query. Do not limit this Query using {@link Query#limitToFirst(int)} or {@link Query#limitToLast(int)}.
     * @param initialSize Size of the initial limit. InfiniteFireArray will limit your query using this value.
     * @param pageSize Size of incremental limit increase when calling {@link #more()}
     * @param limitToFirst Set to false to reverse the order of the Query. When false InfiniteFireArray will use {@link Query#limitToLast(int)} instead of {@link Query#limitToFirst(int)}.
     * @param fixedItemPositions When true InfiniteFireArray will maintain item positions. {@link ChildEventListener#onChildAdded(DataSnapshot, String) onChildAdded} and {@link ChildEventListener#onChildMoved(DataSnapshot, String)} events will be ignored if necessary to maintain the order.
     */
    public InfiniteFireArray(Class<T> ItemClass, Query query, int initialSize, int pageSize, boolean limitToFirst, boolean fixedItemPositions) {
        this.ItemClass = ItemClass;
        this.originalQuery = query;
        this.initialSize = initialSize;
        this.pageSize = pageSize;
        this.limitToFirst = limitToFirst;
        this.fixedItemPositions = fixedItemPositions;
        reset();
    }

    /**
     * Reset the limit of the Query to the initial page size.
     */
    public void reset() {
        cleanupCurrentQuery();
        currentLimit = initialSize;
        endOfDataReached = false;

        // thanks to firebase there should still be data event after removing listeners.
        // so resetting should at this point should not be too bad.

        eraseOnData = true;
        loading = true;
        refreshing = true;
        notifyOnLoadingStatusListener();
        setupCurrentQuery();
    }

    /**
     * Incrementally increase the limit of the Query by one page.
     * Calling more while loading will be ignored.
     */
    public void more() {
        // we do not want to raise the limit if end of data is reached.
        // neither when the limit has just been raised but new data has yet to arrive.

        if(!hasMoreData()) {
            return;
        }
        if(loading) {
            return;
        }
        cleanupCurrentQuery();
        currentLimit += pageSize;
        loading = true;
        notifyOnLoadingStatusListener();
        setupCurrentQuery();
    }

    /**
     * Cleanup listeners.
     */
    public void cleanup() {
        cleanupCurrentQuery();
    }

    /**
     * Use with caution!
     * Remove an item from the array without receiving a Firebase event first.
     * @param index Index of the item that will be removed.
     */
    public void forceRemoveItemAt(int index) {
        dataSnapshots.remove(index);
        notifyOnChangedListener(OnChangedListener.EventType.Removed, index);
    }

    /**
     * @return Is false when the initial sync procedure has been completed. Will be true if {@link #more()} or {@link #reset()} sync procedures are active.
     */
    public boolean isLoading() {
        return loading;
    }

    /**
     * @return Is true only if the initial sync procedure or the {@link #reset()} sync procedure is active.
     */
    public boolean isRefreshing() {
        return refreshing;
    }

    /**
     * @param position Position of the item in the array.
     * @return A snapshot with the key and the typed value.
     */
    public InfiniteFireSnapshot<Object> getItem(int position) {
        DataSnapshot dataSnapshot = dataSnapshots.get(position);
        return new InfiniteFireSnapshot<>(dataSnapshot.getKey(), dataSnapshot.getValue());
    }

    /**
     * @return If true calling {@link #more()} will increase the size of the Query.
     */
    public boolean hasMoreData() {
        return !endOfDataReached;
    }

    /**
     * @return Size of the InfiniteFireArray.
     */
    public int getCount() {
        return dataSnapshots.size();
    }

    /**
     * @param onChangedListener Listener for change events.
     */
    public void setOnChangedListener(OnChangedListener onChangedListener) {
        this.onChangedListener = onChangedListener;
    }

    /**
     * @param onFirebaseErrorListener Listener for error events.
     */
    public void setOnFirebaseErrorListener(OnFirebaseErrorListener onFirebaseErrorListener) {
        this.onFirebaseErrorListener = onFirebaseErrorListener;
    }

    /**
     * @param onLoadingStatusListener Listener for loading events.
     */
    public void addOnLoadingStatusListener(OnLoadingStatusListener onLoadingStatusListener) {
        onLoadingStatusListeners.add(onLoadingStatusListener);
    }

    /**
     * @param onLoadingStatusListener listener for loading events to remove.
     */
    public void removeOnLoadingStatusListener(OnLoadingStatusListener onLoadingStatusListener) {
        Iterator i = onLoadingStatusListeners.iterator();
        while( i.hasNext() ) {
            if (onLoadingStatusListener == i.next()) {
                i.remove();
            }
        }
    }

    /**
     * @param key InfinitFireSnapshot key.
     * @return position of the InfiniteFireSnapshot in the array. -1 if not found.
     */
    public int getIndexForKey(String key) {
        int i = 0;
        for (DataSnapshot snapshot : dataSnapshots) {
            if (snapshot.getKey().equals(key)) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    // --------------------------
    // Listener notify
    // --------------------------

    private void notifyOnChangedListener(OnChangedListener.EventType type) {
        notifyOnChangedListener(type, -1, -1);
    }

    private void notifyOnChangedListener(OnChangedListener.EventType type, int index) {
        notifyOnChangedListener(type, index, -1);
    }

    private void notifyOnChangedListener(OnChangedListener.EventType type, int index, int oldIndex) {
        if(onChangedListener != null) {
            onChangedListener.onChanged(type, index, oldIndex);
        }
    }

    private void notifyOnLoadingStatusListener() {
        Iterator i = onLoadingStatusListeners.iterator();
        OnLoadingStatusListener.EventType type;
        if(loading && dataSnapshots.size() == 0) {
            type = OnLoadingStatusListener.EventType.LoadingNoContent;
        }
        else if(loading) {
            type = OnLoadingStatusListener.EventType.LoadingContent;
        }
        else {
            type = OnLoadingStatusListener.EventType.Done;
        }

        while( i.hasNext() ) {
            OnLoadingStatusListener onLoadingStatusListener = (OnLoadingStatusListener) i.next();
            onLoadingStatusListener.onChanged(type);
        }
    }

    private void notifyOnFirebaseErrorListener(DatabaseError firebaseError) {
        if (onFirebaseErrorListener != null) {
            onFirebaseErrorListener.onFirebaseError(firebaseError);
        }
    }

    // --------------------------
    // Query setup and cleanup
    // --------------------------

    private void setupCurrentQuery() {
        if(limitToFirst) {
            currentQuery = originalQuery.limitToFirst(currentLimit);
        }
        else {
            currentQuery = originalQuery.limitToLast(currentLimit);
        }
        addChildEventListenerToQuery(currentQuery);
        addValueEventListenerToQuery(currentQuery);
    }

    private void cleanupCurrentQuery() {
        if(childEventListener != null) {
            currentQuery.removeEventListener(childEventListener);
        }
        if(valueEventListener != null) {
            currentQuery.removeEventListener(valueEventListener);
        }
        currentQuery = null;
        childEventListener = null;
        valueEventListener = null;
    }

    private void addChildEventListenerToQuery(Query query) {
        childEventListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String previousChild) {
                endOfDataReached = false;
                if(fixedItemPositions) {
                    return;
                }
                boolean firstChild = (dataSnapshots.size() == 0);
                if(eraseOnData) {
                    eraseOnData = false;
                    dataSnapshots.clear();
                    notifyOnChangedListener(OnChangedListener.EventType.Reset);
                }
                int i;
                if(limitToFirst) {
                    if(previousChild != null) {
                        i = getIndexForKey(previousChild) + 1;
                    } else {
                        i = 0;
                    }
                    if(dataSnapshots.size() >= (i + 1) &&
                            dataSnapshots.get(i).getKey().equals(dataSnapshot.getKey())) {
                        return;
                    }
                } else {
                    if(previousChild != null) {
                        // when limit to last is active we want to reverse the order of the children!
                        // so that we can reverse the order again in the LayoutManager.
                        i = getIndexForKey(previousChild);
                    } else {
                        i = dataSnapshots.size();
                    }
                    if(i > 0 &&
                            i < dataSnapshots.size() + 1 &&
                            dataSnapshots.get(i - 1).getKey().equals(dataSnapshot.getKey())) {
                        return;
                    }
                }

                // when previousChild is not supplied this child is supposed to be the first child.
                // unfortunately this is not always true: when added a new child locally, then
                // previousChild might as well be null for multiple children (the first on and the new one).
                // so we need to check the whole array for duplicates.
                if(previousChild == null &&
                        getIndexForKey(dataSnapshot.getKey()) != -1) {
                    return;
                }

                dataSnapshots.add(i, dataSnapshot);
                if(firstChild) {
                    notifyOnLoadingStatusListener();
                }
                notifyOnChangedListener(OnChangedListener.EventType.Added, i);
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String previousChild) {
                int i = getIndexForKey(dataSnapshot.getKey());
                if(i == -1) {
                    return;
                }
                dataSnapshots.set(i, dataSnapshot);
                notifyOnChangedListener(OnChangedListener.EventType.Changed, i);
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                if(fixedItemPositions) {
                    return;
                }
                int i = getIndexForKey(dataSnapshot.getKey());
                if(i == -1) {
                    return;
                }
                dataSnapshots.remove(i);
                notifyOnChangedListener(OnChangedListener.EventType.Removed, i);
            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String previousChild) {
                if(fixedItemPositions) {
                    return;
                }
                int oldIndex = getIndexForKey(dataSnapshot.getKey());
                if(oldIndex == -1) {
                    return;
                }
                dataSnapshots.remove(oldIndex);
                int newIndex;
                if(limitToFirst) {
                    newIndex = (previousChild == null) ? 0 : (getIndexForKey(previousChild) + 1);
                }
                else {
                    newIndex = (previousChild == null) ? dataSnapshots.size() : (getIndexForKey(previousChild));
                }
                dataSnapshots.add(newIndex, dataSnapshot);
                notifyOnChangedListener(OnChangedListener.EventType.Moved, newIndex, oldIndex);
            }

            @Override
            public void onCancelled(DatabaseError firebaseError) {
                cleanup();
                notifyOnFirebaseErrorListener(firebaseError);
            }
        };
        query.addChildEventListener(childEventListener);
    }

    private void addValueEventListenerToQuery(Query query) {
        valueEventListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                long childrenCount = dataSnapshot.getChildrenCount();
                if(childrenCount < currentLimit) {
                    // this event will always be fired after child added events
                    // it will also include all children within the current limit thanks to the event promises.
                    currentLimit = (int) childrenCount;
                    endOfDataReached = true;
                }
                loading = false;
                refreshing = false;
                if(!fixedItemPositions) {
                    notifyOnLoadingStatusListener();
                    return;
                }
                if(eraseOnData) {
                    eraseOnData = false;
                    dataSnapshots.clear();
                    notifyOnChangedListener(OnChangedListener.EventType.Reset);
                }
                int i;
                int length = dataSnapshots.size();
                if(limitToFirst){
                    for(DataSnapshot child : dataSnapshot.getChildren()) {
                        i = getIndexForKey(child.getKey());
                        if(i > -1) continue;
                        dataSnapshots.add(child);
                        notifyOnChangedListener(OnChangedListener.EventType.Added, length -1);
                        length++;
                        // this is to ensure that we never add more items then there should be
                        // which in some cases might be the case in a fixed array (without position changes)
                        // if the firebase data underneath changes too much.
                        if(length > currentLimit) break;
                    }
                }
                else {
                    int firstAddedChildIndex = -1;
                    for(DataSnapshot child : dataSnapshot.getChildren()) {
                        i = getIndexForKey(child.getKey());
                        if (i > -1) continue;
                        if(firstAddedChildIndex == -1) {
                            dataSnapshots.add(child);
                            firstAddedChildIndex = dataSnapshots.size() - 1;
                        }
                        else {
                            dataSnapshots.add(firstAddedChildIndex, child);
                        }
                        notifyOnChangedListener(OnChangedListener.EventType.Added, firstAddedChildIndex);
                        length ++;
                        if(length > currentLimit) break;
                    }
                }
                notifyOnLoadingStatusListener();
            }

            @Override
            public void onCancelled(DatabaseError firebaseError) {
                cleanup();
                notifyOnFirebaseErrorListener(firebaseError);
            }
        };
        query.addListenerForSingleValueEvent(valueEventListener);
    }
}