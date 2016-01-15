package org.glowroot.agent.plugin.hibernate;

import java.io.Serializable;

import javax.persistence.*;

@Entity
public class Employee implements Serializable {
    private static final long serialVersionUID = 1L;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column
    private Integer employeeId;
    @Column
    private String name;

    public Integer getEmployeeId() {
        return employeeId;
    }

    public Employee() {
    }

    public Employee(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}