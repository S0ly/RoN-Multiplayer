package com.ron.instance;

public enum InstanceState {
    IDLE,       // Booted, no fresh map, waiting for load command
    PREPARING,  // Received load command, about to halt
    READY,      // Booted with fresh map, waiting for players
    RUNNING,    // Match in progress
    FINISHED    // Match ended, waiting for proxy to transfer players
}
