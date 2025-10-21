package com.example.naomimode;

public class EventTextMapper {
    // 文本显示
    public static String Text(String event, String roomId, String floorId, String state, String error) {
        String main = "";

        switch (event) {
            case "arrive":
                main = (roomId == null || roomId.isEmpty())
                        ? "部屋に到着しました" : ("部屋 " + roomId + " に到着しました");
                break;
            case "leave":
                main = "戻ります";
                break;
            case "charging":
                main = "充電中";
                break;
            case "emergency":
                main = "緊急ボタンが押されました";
                break;
            case "alarm":
                main = "ロボットが助けを求めています";
                break;
            case "delivery_running":
                main = (roomId == null || roomId.isEmpty())
                        ? "部屋へ配送中" : (roomId + " 部屋へ配送中");
                break;
            case "delivery_success":
                main = "お客様による取り出し完了";
                break;
            case "delivery_fail":
                main = "受け取りできません。品物を取り出してください。";
                break;
            case "elv_wait":
                main = (floorId == null || floorId.isEmpty())
                        ? "エレベーターを待っています" : (floorId + " エレベーターを待っています");
                break;
            case "elv_in":
                main = (floorId == null || floorId.isEmpty())
                        ? "エレベーターに乗ります" : (floorId + " エレベーターに乗ります");
                break;
            case "elv_out":
                main = (floorId == null || floorId.isEmpty())
                        ? "エレベーターを降ります" : (floorId + " エレベーターを降ります");
                break;
            default:
                main = "更新：" + event;
        }
        return main;
    }
}
