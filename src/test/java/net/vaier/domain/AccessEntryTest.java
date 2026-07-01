package net.vaier.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessEntryTest {

    private static AccessEntry entry(Role role, List<String> groups) {
        return AccessEntry.builder().email("a@example.com").role(role).groups(groups).build();
    }

    // --- isPending ---

    @Test
    void isPending_trueOnlyForPendingRole() {
        assertThat(entry(Role.PENDING, List.of()).isPending()).isTrue();
        assertThat(entry(Role.USER, List.of()).isPending()).isFalse();
        assertThat(entry(Role.ADMIN, List.of()).isPending()).isFalse();
    }

    // --- isAdmin ---

    @Test
    void isAdmin_trueOnlyForAdminRole() {
        assertThat(entry(Role.ADMIN, List.of()).isAdmin()).isTrue();
        assertThat(entry(Role.USER, List.of()).isAdmin()).isFalse();
        assertThat(entry(Role.PENDING, List.of()).isAdmin()).isFalse();
    }

    // --- mayAccessConsole — admin only ---

    @Test
    void mayAccessConsole_trueOnlyForAdmin() {
        assertThat(entry(Role.ADMIN, List.of()).mayAccessConsole()).isTrue();
        assertThat(entry(Role.USER, List.of("admins")).mayAccessConsole()).isFalse();
        assertThat(entry(Role.PENDING, List.of()).mayAccessConsole()).isFalse();
    }

    // --- mayAccessService ---

    @Test
    void mayAccessService_pendingIsAlwaysDenied() {
        assertThat(entry(Role.PENDING, List.of("family")).mayAccessService("family")).isFalse();
        assertThat(entry(Role.PENDING, List.of()).mayAccessService(null)).isFalse();
    }

    @Test
    void mayAccessService_adminIsAlwaysAllowed() {
        assertThat(entry(Role.ADMIN, List.of()).mayAccessService("family")).isTrue();
        assertThat(entry(Role.ADMIN, List.of()).mayAccessService("anything")).isTrue();
    }

    @Test
    void mayAccessService_userAllowedWhenInRequiredGroup() {
        assertThat(entry(Role.USER, List.of("family", "media")).mayAccessService("family")).isTrue();
    }

    @Test
    void mayAccessService_userDeniedWhenNotInRequiredGroup() {
        assertThat(entry(Role.USER, List.of("media")).mayAccessService("family")).isFalse();
    }

    @Test
    void mayAccessService_userWithNullGroupsDeniedForRequiredGroup() {
        assertThat(entry(Role.USER, null).mayAccessService("family")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void mayAccessService_blankRequiredGroupAllowsAnyNonPendingUser(String requiredGroup) {
        assertThat(entry(Role.USER, List.of()).mayAccessService(requiredGroup)).isTrue();
        assertThat(entry(Role.ADMIN, List.of()).mayAccessService(requiredGroup)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void mayAccessService_blankRequiredGroupStillDeniesPending(String requiredGroup) {
        assertThat(entry(Role.PENDING, List.of()).mayAccessService(requiredGroup)).isFalse();
    }

    // --- role-mirroring groups — 'admins'/'users' are the Role's job, never access groups ---

    @Test
    void hasRoleMirroringGroups_trueWhenGroupsContainAdmins() {
        assertThat(entry(Role.ADMIN, List.of("devs", "admins")).hasRoleMirroringGroups()).isTrue();
    }

    @Test
    void hasRoleMirroringGroups_trueWhenGroupsContainUsers() {
        assertThat(entry(Role.USER, List.of("users")).hasRoleMirroringGroups()).isTrue();
    }

    @Test
    void hasRoleMirroringGroups_falseForPlainServiceGroups() {
        assertThat(entry(Role.USER, List.of("devs", "family")).hasRoleMirroringGroups()).isFalse();
    }

    @Test
    void hasRoleMirroringGroups_falseWhenGroupsEmptyOrNull() {
        assertThat(entry(Role.ADMIN, List.of()).hasRoleMirroringGroups()).isFalse();
        assertThat(entry(Role.ADMIN, null).hasRoleMirroringGroups()).isFalse();
    }

    @Test
    void withoutRoleMirroringGroups_stripsAdminsAndUsersKeepingServiceGroups() {
        AccessEntry cleaned = entry(Role.ADMIN, List.of("devs", "admins", "users", "family"))
                .withoutRoleMirroringGroups();
        assertThat(cleaned.getGroups()).containsExactly("devs", "family");
    }

    @Test
    void withoutRoleMirroringGroups_preservesEmailRoleAndName() {
        AccessEntry original = AccessEntry.builder()
                .email("geir@example.com").role(Role.ADMIN)
                .groups(List.of("devs", "admins")).name("Geir").build();

        AccessEntry cleaned = original.withoutRoleMirroringGroups();

        assertThat(cleaned.getEmail()).isEqualTo("geir@example.com");
        assertThat(cleaned.getRole()).isEqualTo(Role.ADMIN);
        assertThat(cleaned.getName()).isEqualTo("Geir");
        assertThat(cleaned.getGroups()).containsExactly("devs");
    }

    @Test
    void withoutRoleMirroringGroups_nullGroupsStaysEmpty() {
        assertThat(entry(Role.USER, null).withoutRoleMirroringGroups().getGroups()).isEmpty();
    }

    // --- resolvedName — the display-name capture decision (never wipes a known name) ---

    @Test
    void resolvedName_usesTrimmedHeaderWhenPresent() {
        AccessEntry e = AccessEntry.builder().email("a@e.com").role(Role.USER).groups(List.of()).build();
        assertThat(e.resolvedName("  Alice Smith  ")).isEqualTo("Alice Smith");
    }

    @Test
    void resolvedName_headerRefreshesAnExistingName() {
        AccessEntry e = AccessEntry.builder().email("a@e.com").role(Role.USER).groups(List.of()).name("Old").build();
        assertThat(e.resolvedName("New")).isEqualTo("New");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void resolvedName_keepsExistingNameWhenHeaderBlank(String header) {
        AccessEntry e = AccessEntry.builder().email("a@e.com").role(Role.USER).groups(List.of()).name("Alice").build();
        assertThat(e.resolvedName(header)).isEqualTo("Alice");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void resolvedName_nullWhenNoExistingNameAndBlankHeader(String header) {
        AccessEntry e = AccessEntry.builder().email("a@e.com").role(Role.USER).groups(List.of()).build();
        assertThat(e.resolvedName(header)).isNull();
    }

    // --- Role.fromString — file values are lowercase, hand-edits may vary case ---

    @Test
    void roleFromString_parsesCanonicalLowercaseValues() {
        assertThat(Role.fromString("admin")).isEqualTo(Role.ADMIN);
        assertThat(Role.fromString("user")).isEqualTo(Role.USER);
        assertThat(Role.fromString("pending")).isEqualTo(Role.PENDING);
    }

    @Test
    void roleFromString_isCaseInsensitive() {
        assertThat(Role.fromString("ADMIN")).isEqualTo(Role.ADMIN);
        assertThat(Role.fromString(" User ")).isEqualTo(Role.USER);
    }

    @Test
    void roleFromString_unknownOrBlankDefaultsToPending() {
        assertThat(Role.fromString("nonsense")).isEqualTo(Role.PENDING);
        assertThat(Role.fromString(null)).isEqualTo(Role.PENDING);
        assertThat(Role.fromString("  ")).isEqualTo(Role.PENDING);
    }

    @Test
    void roleWireValue_isLowercase() {
        assertThat(Role.ADMIN.wireValue()).isEqualTo("admin");
        assertThat(Role.USER.wireValue()).isEqualTo("user");
        assertThat(Role.PENDING.wireValue()).isEqualTo("pending");
    }
}
