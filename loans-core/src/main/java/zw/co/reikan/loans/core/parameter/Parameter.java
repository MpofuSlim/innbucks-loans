package zw.co.reikan.loans.core.parameter;

import lombok.Data;
import zw.co.reikan.loans.core.loan.BaseEntity;

import jakarta.persistence.*;
import java.util.Objects;

@Data
@Entity
@Table(name = "parameter", indexes = {@Index(name = "indx_parameter_name", columnList = "name", unique = true)})
public class Parameter extends BaseEntity {

    @Column(name = "name", unique = true, length = 50)
    private String name;

    @Column(name = "val", length = 200, nullable = false)
    private String value;

    @Column(name = "user_can_edit")
    private Boolean editable;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Parameter parameter = (Parameter) o;
        return Objects.equals(value, parameter.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
