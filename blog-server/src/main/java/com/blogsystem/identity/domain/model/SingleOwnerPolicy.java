package com.blogsystem.identity.domain.model;

/**
 * The only principal that may authenticate or use an existing session.
 */
public final class SingleOwnerPolicy {
    private final String phone;

    public SingleOwnerPolicy(String phone) {
        this.phone = new PhoneNumber(phone).value();
    }

    public boolean acceptsPhone(String candidate) {
        return phone.equals(candidate);
    }

    public boolean accepts(OwnerAccount account) {
        return account != null && account.active() && acceptsPhone(account.phone());
    }
}
