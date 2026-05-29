package com.sashkomusic.agents.library;

public sealed interface LibraryCommand {
    record Rate(int stars) implements LibraryCommand {}
    record SetEnergy(String level) implements LibraryCommand {}
    record SetFunction(String function) implements LibraryCommand {}
    record AddComment(String text) implements LibraryCommand {}
    record Unknown(String reason) implements LibraryCommand {}
}
