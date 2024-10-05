package zw.co.reikan.loans.core.api;

import lombok.Data;

@Data
public class SearchUserRequest {

    private String searchText;

    private Integer pageSize;

    private Integer pageNumber;
}
