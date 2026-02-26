package com.wireweave.domain.port;

import com.wireweave.domain.User;
import java.util.List;

public interface ForPersistingUsers {
    List<User> getUsers();
}
