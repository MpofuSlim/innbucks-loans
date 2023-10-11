package zw.co.reikan.loans.core.user;


import java.util.List;
import java.util.Optional;

public interface UserService {

    String create(UserRequest request);

    Optional<User> findActiveUserByUsername(String username);

    Optional<User> findById(Long id);

    List<User> findByAllUsers();

    User save(User user);

    void recoverPassword(String username);

    Optional<User> findUserByUsername(String username);
}
