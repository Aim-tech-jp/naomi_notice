package com.example.naomimode;

public class EventTextMapper {
    // 文本显示
    public static String Text(String event, String roomId, String floorId, String state, String error) {
        String main = "";

        switch (event) {
            case "arrive":
//                main = (roomId == null || roomId.isEmpty())
//                        ? "執務室の前に到着いたしました" : ("部屋 " + roomId + " に到着しました");
                main = "執務室の前に到着いたしました";
                SlackLogger.log("niigata", roomId + "執務室の前に到着いたしました");
                break;
//            case "leave":
//                main = (roomId == null || roomId.isEmpty())
//                        ? "戻ります" : (roomId + "から戻ります");
//                break;
            case "delivery_running":
                main = "今からロボットが執務室へ配送します";
                SlackLogger.log("niigata", roomId + "今からロボットが執務室へ配送します");
                break;
            case "charging":
                    main = "充電中";
                break;
            case "initialize_done":
                    main = "待機中";
                break;
            case "emergency":
                if ("on".equals(state)){
                    main = "緊急ボタンが押されました";
                }else {
                    main = "更新待ち!!";
                }
                break;
//            case "alarm":
//                main = (error == null || error.isEmpty())
//                        ? "ロボットが助けを求めています" : (error + " \nロボットが助けを求めています");
//                break;
//            case "delivery_running":
//                main = (roomId == null || roomId.isEmpty())
//                        ? "部屋へ配送中" : (roomId + " 部屋へ配送中");
//                break;
            case "delivery_success":
                main = "受取完了しました";
                SlackLogger.log("niigata", roomId + "受取完了しました");
                break;
            case "delivery_fail":
                main = "荷物を受け取れませんでした。";
                break;
//            case "elv_wait":
//                main = (floorId == null || floorId.isEmpty())
//                        ? "エレベーターを待っています" : (floorId + "階 エレベーターを待っています");
//                break;
//            case "elv_in":
//                main = (floorId == null || floorId.isEmpty())
//                        ? "エレベーターに乗ります" : (floorId + "階 エレベーターに乗ります");
//                break;
//            case "elv_out":
//                main = (floorId == null || floorId.isEmpty())
//                        ? "エレベーターを降ります" : (floorId + "階 エレベーターを降ります");
//                break;
            default:
                main = "更新待ち!!!";
        }
        return main;
    }
}
