package zw.co.reikan.loans.core.exception;

import java.text.MessageFormat;

public class DuplicateUserByUsernameException extends BusinessException {

	private final String username;
	
	public DuplicateUserByUsernameException(String username) {
		super(MessageFormat.format("User {0} already exists", username));
		this.username = username;
	}

	public String getUsername() {
		return username;
	}
	
	
}
