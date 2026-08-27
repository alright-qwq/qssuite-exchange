package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository {
  TransferRecord create(TransferRecord prepared) throws SQLException;

  Optional<TransferRecord> find(UUID transferId) throws SQLException;

  Optional<TransferRecord> findByRequest(UUID accountId, UUID requestId) throws SQLException;

  List<TransferRecord> findUnfinished(UUID accountId) throws SQLException;

  List<TransferRecord> findAllUnfinished() throws SQLException;

  TransferRecord transition(UUID transferId, long expectedVersion,
                            TransferStatus expectedStatus, TransferStatus targetStatus,
                            String reason) throws SQLException;

  TransferRecord transitionGuarded(UUID transferId, long expectedVersion,
                                   TransferStatus expectedStatus, TransferStatus targetStatus,
                                   RecoveryEvidence evidence, String reason) throws SQLException;
}
