package net.vaier.domain;

import lombok.Value;

import java.util.List;

@Value
public class User {
    String name;
    String displayname;
    String email;
    List<String> groups;
}
