package com.semojum.backend.domain.support.repository;

import com.semojum.backend.domain.support.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByOrganizationIdOrderByOrderDateDesc(UUID organizationId);

    List<Order> findAllByOrderByOrderDateDesc();
}
