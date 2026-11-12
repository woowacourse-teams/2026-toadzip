package com.toadzip.backend.user.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.exception.AdminUserNotFoundException;
import com.toadzip.backend.user.exception.LastAdminRevokeException;
import com.toadzip.backend.user.exception.SelfAdminRevokeException;
import com.toadzip.backend.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 화면에서 카카오·구글 회원의 관리자 권한을 주고 뺀다. 바뀐 경우에만 admin_data_changes에 남긴다.
 * actor는 다른 관리자 작업과 같이 세션 이름(비밀번호 관리자는 로그인 이름, 소셜 관리자는 회원 번호)이다.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserRoleService {

    private static final String RESOURCE = "USER";

    private final UserRepository users;
    private final AdminDataChangeRepository changes;

    @Transactional
    public void grant(long userId, String actor) {
        User user = lockedUser(userId);
        if (user.isAdmin()) {
            return;
        }
        user.grantAdmin();
        changes.save(new AdminDataChange(RESOURCE, userId, "GRANT_ADMIN", actor,
                "{\"admin\":false}", "{\"admin\":true}"));
    }

    /**
     * 자기 자신의 권한과 마지막 소셜 관리자의 권한은 뺄 수 없다. 소셜 관리자가 빼면 대상과 자신의 회원 행을 번호 순서로 잠근 뒤
     * 자신이 아직 관리자인지 다시 확인해, 두 관리자가 동시에 서로의 권한을 빼 관리자가 모두 사라지는 일을 막는다.
     */
    @Transactional
    public void revoke(long userId, Authentication actor) {
        User user;
        if (SocialSessions.isSocialSession(actor)) {
            long actorId = Long.parseLong(actor.getName());
            if (actorId == userId) {
                throw new SelfAdminRevokeException();
            }
            User first = lockedUser(Math.min(actorId, userId));
            User second = lockedUser(Math.max(actorId, userId));
            User actorUser = actorId < userId ? first : second;
            if (!actorUser.isAdmin()) {
                throw new AccessDeniedException("관리자 권한이 필요합니다.");
            }
            user = actorId < userId ? second : first;
        } else {
            user = lockedLastAdminGuarded(userId);
        }
        if (user == null || !user.isAdmin()) {
            return;
        }
        user.revokeAdmin();
        changes.save(new AdminDataChange(RESOURCE, userId, "REVOKE_ADMIN", actor.getName(),
                "{\"admin\":true}", "{\"admin\":false}"));
    }

    /**
     * 비밀번호 관리자가 빼면 관리자 회원 행을 모두 번호 순서로 잠그고 세어, 관리자 권한이 있는 소셜 계정이 하나도 남지 않게 되면 막는다.
     * 대상이 잠근 관리자 목록에 없으면 바꿀 것이 없으므로 대상 행을 더 잠그지 않고 null을 돌려준다.
     * 번호 순서를 벗어난 잠금이 생기지 않아 소셜 경로와 교착하지 않고, 그사이 들어온 권한 부여를 개수 검사 없이 빼는 일도 없다.
     */
    private User lockedLastAdminGuarded(long userId) {
        List<User> admins = users.findAdminsForUpdate();
        User target = admins.stream().filter(admin -> admin.getId() == userId).findFirst().orElse(null);
        if (target == null) {
            if (!users.existsById(userId)) {
                throw new AdminUserNotFoundException();
            }
            return null;
        }
        if (admins.size() == 1) {
            throw new LastAdminRevokeException();
        }
        return target;
    }

    private User lockedUser(long userId) {
        return users.findForUpdateById(userId).orElseThrow(AdminUserNotFoundException::new);
    }
}
