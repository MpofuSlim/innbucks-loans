package zw.co.reikan.nanoloansweb.parameter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ParameterRepository extends JpaRepository<Parameter, Long> {

    Parameter findByName(String parameterName);

    List<Parameter> findByNameIn(String... parameterNames);
}
