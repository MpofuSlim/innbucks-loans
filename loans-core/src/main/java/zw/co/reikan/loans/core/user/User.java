package zw.co.reikan.loans.core.user;


import lombok.Data;
import zw.co.reikan.loans.core.loan.BaseEntity;

import jakarta.persistence.*;
import zw.co.reikan.loans.core.merchant.Merchant;

@Entity
@Table(indexes = {
    @Index(name = "idx_external_system_id", columnList = "externalSystemId")
})
@Data
public class User extends BaseEntity {

	@Column(nullable = false, length = 100, unique = true)
	private String username;
	
	@Column(nullable = false, length = 100, unique = true)
	private String externalSystemId;

	@ManyToOne(optional = false)
	private Merchant merchant;
	
	@Column(nullable = false)
	private Boolean temporaryPassword;

}
