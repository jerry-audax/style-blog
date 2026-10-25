package com.blogsystem.application.identity.command;

public record PasswordLoginCommand(String phone, String password) {
    @Override
    public String toString() {
        return "PasswordLoginCommand[REDACTED]";
    }
}
