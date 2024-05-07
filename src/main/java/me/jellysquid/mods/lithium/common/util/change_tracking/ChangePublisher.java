package me.jellysquid.mods.lithium.common.util.change_tracking;

public interface ChangePublisher<T> {
    boolean lithium$subscribe(ChangeSubscriber<T> subscriber, int subscriberData);

    void lithium$unsubscribe(ChangeSubscriber<T> subscriber);
}
