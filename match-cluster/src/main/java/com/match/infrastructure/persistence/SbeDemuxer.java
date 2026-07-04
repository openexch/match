// SPDX-License-Identifier: Apache-2.0
/*
 * Copyright 2023 Adaptive Financial Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.commands.UpdateOrderCommand;
import com.match.infrastructure.generated.CancelOrderDecoder;
import com.match.infrastructure.generated.CreateOrderDecoder;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.UpdateOrderDecoder;
import org.agrona.DirectBuffer;

/**
 * Ultra-low latency SBE demultiplexer.
 * ZERO allocations, ZERO string parsing in hot path.
 */
public class SbeDemuxer {
    private final Engine engine;
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    // SBE decoders (reused)
    private final CancelOrderDecoder cancelOrderDecoder = new CancelOrderDecoder();
    private final CreateOrderDecoder createOrderDecoder = new CreateOrderDecoder();
    private final UpdateOrderDecoder updateOrderDecoder = new UpdateOrderDecoder();

    // Pooled command objects (reused, never allocated per message)
    private final CancelOrderCommand cancelCommand = new CancelOrderCommand();
    private final CreateOrderCommand createCommand = new CreateOrderCommand();
    private final UpdateOrderCommand updateCommand = new UpdateOrderCommand();

    // P1.5 diag counter (match#32): CreateOrder commands accepted off ingress.
    // Plain long: written and read on the cluster service thread only.
    private long createOrderCount;

    /** P1.2 (match#31): receives logged RequestOpenOrdersSnapshot commands. */
    public interface OpenOrdersSnapshotRequestHandler {
        void onOpenOrdersSnapshotRequest(long requestId);
    }

    private final com.match.infrastructure.generated.RequestOpenOrdersSnapshotDecoder
            requestOpenOrdersDecoder = new com.match.infrastructure.generated.RequestOpenOrdersSnapshotDecoder();
    private OpenOrdersSnapshotRequestHandler openOrdersSnapshotRequestHandler;

    public void setOpenOrdersSnapshotRequestHandler(OpenOrdersSnapshotRequestHandler handler) {
        this.openOrdersSnapshotRequestHandler = handler;
    }

    public SbeDemuxer(Engine engine) {
        this.engine = engine;
    }

    /**
     * Dispatch a message to the appropriate handler.
     * ZERO allocations, direct primitive access.
     */
    public void dispatch(final DirectBuffer buffer, final int offset, final int length, final long timestamp) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) {
            return;
        }

        headerDecoder.wrap(buffer, offset);

        switch (headerDecoder.templateId()) {
            case CreateOrderDecoder.TEMPLATE_ID:
                handleCreateOrder(buffer, offset, timestamp);
                break;

            case CancelOrderDecoder.TEMPLATE_ID:
                handleCancelOrder(buffer, offset, timestamp);
                break;

            case UpdateOrderDecoder.TEMPLATE_ID:
                handleUpdateOrder(buffer, offset, timestamp);
                break;

            case com.match.infrastructure.generated.RequestOpenOrdersSnapshotDecoder.TEMPLATE_ID:
                // P1.2 (match#31): logged command; deterministic across replicas
                // (no state mutation — only the leader emits the egress reply).
                requestOpenOrdersDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                if (openOrdersSnapshotRequestHandler != null) {
                    openOrdersSnapshotRequestHandler.onOpenOrdersSnapshotRequest(
                            requestOpenOrdersDecoder.requestId());
                }
                break;

            default:
                // Unknown message - ignore in hot path
                break;
        }
    }

    public long createOrderCount() {
        return createOrderCount;
    }

    private void handleCreateOrder(DirectBuffer buffer, int offset, long timestamp) {
        createOrderCount++;
        createOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        // Direct primitive access - NO string parsing, NO allocations
        createCommand.reset();
        createCommand.setUserId(createOrderDecoder.userId());
        createCommand.setPrice(createOrderDecoder.price());
        createCommand.setQuantity(createOrderDecoder.quantity());
        createCommand.setTotalPrice(createOrderDecoder.totalPrice());
        createCommand.setOrderSide(toDomainOrderSide(createOrderDecoder.orderSide()));
        createCommand.setOrderType(toDomainOrderType(createOrderDecoder.orderType()));
        createCommand.setOmsOrderId(createOrderDecoder.omsOrderId());

        int marketId = createOrderDecoder.marketId();
        engine.acceptOrder(marketId, Engine.CMD_CREATE, createCommand, timestamp);
    }

    private void handleCancelOrder(DirectBuffer buffer, int offset, long timestamp) {
        cancelOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        // Direct primitive access - NO string parsing
        cancelCommand.reset();
        cancelCommand.setUserId(cancelOrderDecoder.userId());
        cancelCommand.setOrderId(cancelOrderDecoder.orderId());

        int marketId = cancelOrderDecoder.marketId();
        engine.acceptOrder(marketId, Engine.CMD_CANCEL, cancelCommand, timestamp);
    }

    private void handleUpdateOrder(DirectBuffer buffer, int offset, long timestamp) {
        updateOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        // Direct primitive access - NO string parsing
        updateCommand.reset();
        updateCommand.setUserId(updateOrderDecoder.userId());
        updateCommand.setOrderId(updateOrderDecoder.orderId());
        updateCommand.setPrice(updateOrderDecoder.price());
        updateCommand.setQuantity(updateOrderDecoder.quantity());
        updateCommand.setOrderSide(toDomainOrderSide(updateOrderDecoder.orderSide()));
        updateCommand.setOrderType(toDomainOrderType(updateOrderDecoder.orderType()));

        int marketId = updateOrderDecoder.marketId();
        engine.acceptOrder(marketId, Engine.CMD_UPDATE, updateCommand, timestamp);
    }

    private com.match.domain.enums.OrderType toDomainOrderType(com.match.infrastructure.generated.OrderType sbeOrderType) {
        switch (sbeOrderType) {
            case LIMIT:
                return com.match.domain.enums.OrderType.LIMIT;
            case MARKET:
                return com.match.domain.enums.OrderType.MARKET;
            case LIMIT_MAKER:
                return com.match.domain.enums.OrderType.LIMIT_MAKER;
            default:
                return com.match.domain.enums.OrderType.LIMIT;
        }
    }

    private com.match.domain.enums.OrderSide toDomainOrderSide(com.match.infrastructure.generated.OrderSide sbeOrderSide) {
        switch (sbeOrderSide) {
            case BID:
                return com.match.domain.enums.OrderSide.BID;
            case ASK:
                return com.match.domain.enums.OrderSide.ASK;
            default:
                return com.match.domain.enums.OrderSide.BID;
        }
    }
}
