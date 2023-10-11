package zw.co.reikan.loans.core.user;

import lombok.extern.slf4j.Slf4j;
import org.passay.CharacterRule;
import org.passay.PasswordGenerator;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

import static org.passay.EnglishCharacterData.Digit;
import static org.passay.EnglishCharacterData.LowerCase;
import static org.passay.EnglishCharacterData.UpperCase;


@Slf4j
@Service
public class PasswordPolicyServiceImpl implements PasswordPolicyService {

    private final PasswordGenerator passwordGenerator;

    List<CharacterRule> characterRules;

    public PasswordPolicyServiceImpl() {
        characterRules = Arrays.asList(new CharacterRule(UpperCase, 2),
                new CharacterRule(LowerCase, 2),
                new CharacterRule(Digit, 2));
        passwordGenerator = new PasswordGenerator();
    }

    @Override
    public String generatePassword() {
        return passwordGenerator.generatePassword(8, characterRules);
    }

}
