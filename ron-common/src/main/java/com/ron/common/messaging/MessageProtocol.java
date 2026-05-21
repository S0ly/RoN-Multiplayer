package com.ron.common.messaging;

public final class MessageProtocol {

    private MessageProtocol() {}

    public static final class Channels {
        public static final String TRANSFER = "ron:transfer";
        public static final String MATCH = "ron:match";

        private Channels() {}
    }

    // Lobby -> Proxy actions (sent in the "action" field on ron:match)
    public static final class Action {
        public static final String FIND_MATCH = "find_match";
        public static final String GET_MAPS = "get_maps";
        public static final String CONFIRM_MATCH = "confirm_match";
        public static final String CANCEL_MATCH = "cancel_match";
        public static final String GET_INFO = "get_info";
        public static final String GET_RANK = "get_rank";
        public static final String GET_LEADERBOARD = "get_leaderboard";
        public static final String QUEUE_UPDATE = "queue_update";

        private Action() {}
    }

    // Proxy -> Lobby response types (sent in the "type" field on ron:match)
    public static final class Type {
        public static final String INFO = "info";
        public static final String MAP_OPTIONS = "map_options";
        public static final String RANK_RESPONSE = "rank_response";
        public static final String LEADERBOARD_RESPONSE = "leaderboard_response";
        public static final String INSTANCE_READY = "instance_ready";

        private Type() {}
    }
}
