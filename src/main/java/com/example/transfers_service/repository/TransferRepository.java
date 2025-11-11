package com.example.transfers_service.repository;

import com.example.transfers_service.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, String> {
    // En caso de necesitar consultas personalizadas, se agregan aquí
    @Query(value = "SELECT LPAD(\n" +
            "         IFNULL(MAX(CAST(RIGHT(transfer_id, 8) AS UNSIGNED)), 0) + 1,\n" +
            "         8,\n" +
            "         '0'\n" +
            "       )\n" +
            "FROM transfers;", nativeQuery = true)
    String nextTransferId();
}
