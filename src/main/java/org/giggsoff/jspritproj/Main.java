package org.giggsoff.jspritproj;

import com.graphhopper.GHResponse;
import com.graphhopper.PathWrapper;
import org.giggsoff.jspritproj.utils.Reader;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang.time.DateUtils;
import org.giggsoff.jspritproj.jenetics.CostsInterface;
import org.giggsoff.jspritproj.jenetics.Evaluator;
import org.giggsoff.jspritproj.jenetics.Mark;
import org.giggsoff.jspritproj.jenetics.SituationInterface;
import org.giggsoff.jspritproj.models.Dump;
import org.giggsoff.jspritproj.models.Point;
import org.giggsoff.jspritproj.models.Polygon;
import org.giggsoff.jspritproj.models.Region;
import org.giggsoff.jspritproj.models.SGB;
import org.giggsoff.jspritproj.models.Truck;
import org.giggsoff.jspritproj.utils.GeoJson;
import org.giggsoff.jspritproj.utils.GraphhopperWorker;
import org.giggsoff.jspritproj.utils.Solver;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Main {

    public static List<Truck> trList = new ArrayList<>();
    public static List<SGB> sgbList = new ArrayList<>();
    public static List<Dump> dumpList = new ArrayList<>();
    public static List<Region> regionList = new ArrayList<>();
    public static GraphhopperWorker gw = null;
    public static MongoClient mongo = null;
    public static HashMap<String, List<String>> lastList = new HashMap<>();

    public static void main(String[] args) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
            server.createContext("/test", new MyHandler());
            server.createContext("/get_routes", new RealHandler());
            server.createContext("/get_routes_gen", new GeneticHandler());
            server.createContext("/get_work", new WorkHandler());
            server.start();
        } catch (IOException | JSONException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
        try {
            mongo = new MongoClient("77.234.220.206", 27016);
            List<String> dbs = mongo.getDatabaseNames();
            System.out.println(dbs);
        } catch (Exception ex){
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);            
        }
        /*
         * some preparation - create output folder
         */
        File dir = new File("output");
        // if the directory does not exist, create it
        if (!dir.exists()) {
            System.out.println("creating directory ./output");
            boolean result = dir.mkdir();
            if (result) {
                System.out.println("./output created");
            }
        }
        try {
            gw = new GraphhopperWorker("map.pbf", "output");
            System.out.println("Ready");
            //doWorkGenetic(null, new JSONArray(), 2300.);
        } catch (IOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    static void doWork(HttpExchange he, boolean show, JSONArray regs, Double maxTime) {
        try {
            List<Region> rlist = Region.fromArray(Reader.readArray("get_regions"));
            regionList = new ArrayList<>();
            if (regs.length() == 0) {
                regionList.addAll(rlist);
            } else {
                for (Region r : rlist) {
                    regs.forEach(item -> {
                        if (item.equals(r.id) && !regionList.contains(r)) {
                            regionList.add(r);
                        }
                    });
                }
            }
            List<Truck> tlist = Truck.fromArray(Reader.readArray("get_truck?region=" + regionList.get(0).id));
            trList = new ArrayList<>();
            trList.addAll(tlist);
            trList.sort((Truck o1, Truck o2) -> o2.priority - o1.priority);
            List<SGB> list = SGB.fromArray(Reader.readArray("get_sgb?region=" + regionList.get(0).id));
            sgbList = new ArrayList<>();
            sgbList.addAll(list);
            List<Dump> dlist = Dump.fromArray(Reader.readArray("get_waste_dumps?region=" + regionList.get(0).id));
            dumpList = new ArrayList<>();
            dumpList.addAll(dlist);
            Long t = 0l;
            List<Polygon> ar = new ArrayList<>();
            int trCount = trList.size();
            do {
                VehicleRoutingProblemSolution solve = Solver.solve(trList.subList(0, trCount), sgbList, dumpList, gw, show);
                if (solve.getUnassignedJobs().size() > 0 && ar.size() > 0) {
                    break;
                }
                ar = new ArrayList<>();
                List<Long> maxT = new ArrayList<>();
                for (VehicleRoute vr : solve.getRoutes()) {
                    maxT.add(0l);
                    List<Point> vehroute = new ArrayList<>();
                    vehroute.add(new Point(vr.getStart().getLocation().getCoordinate(),0,""));
                    for (TourActivity ta : vr.getActivities()) {
                        vehroute.add(new Point(ta.getLocation().getCoordinate(),0,""));
                    }
                    vehroute.add(new Point(vr.getEnd().getLocation().getCoordinate(),0,""));
                    Polygon tcoords = new Polygon();
                    for (int i = 0; i < vehroute.size() - 1; i++) {
                        if (vehroute.get(i).toString().equals(vehroute.get(i + 1).toString())) {
                            continue;
                        }
                        PathWrapper grp = Solver.getRoute(vehroute.get(i), vehroute.get(i + 1), gw);
                        maxT.set(maxT.size() - 1, maxT.get(maxT.size() - 1) + grp.getTime());
                        if (grp != null) {
                            for (int j = 0; j < grp.getPoints().size(); j++) {
                                tcoords.addPoint(new Point(grp.getPoints().getLon(j), grp.getPoints().getLat(j),0,""));
                            }
                        }
                    }
                    if (tcoords.size() == 0) {
                        tcoords.addPoint(new Point(vehroute.get(0).x, vehroute.get(0).y,0,""));
                    }
                    ar.add(tcoords);
                }
                for (Long l : maxT) {
                    if (l > t) {
                        t = l;
                    }
                }
                trCount -= 1;
            } while (t < maxTime * 1000 && trCount > 0);
            if (!show) {
                String response = GeoJson.getGeoJSON(ar).toString();
                he.getResponseHeaders().set("Content-Type", "application/json");
                he.sendResponseHeaders(200, response.length());
                OutputStream os = he.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        } catch (JSONException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ParseException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    static class MyHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange he) {
            try {
                List<Region> rlist = Region.fromArray(Reader.readArray("get_regions"));
                regionList = new ArrayList<>();
                regionList.addAll(rlist);
                List<Truck> tlist = Truck.fromArray(Reader.readArray("get_truck?region=" + regionList.get(0).id));
                trList = new ArrayList<>();
                trList.addAll(tlist);
                List<SGB> list = SGB.fromArray(Reader.readArray("get_sgb?region=" + regionList.get(0).id));
                sgbList = new ArrayList<>();
                sgbList.addAll(list);
                List<Dump> dlist = Dump.fromArray(Reader.readArray("get_waste_dumps?region=" + regionList.get(0).id));
                dumpList = new ArrayList<>();
                dumpList.addAll(dlist);
                VehicleRoutingProblemSolution solve = Solver.solve(trList, sgbList, dumpList, gw, false);
                JSONArray ar = new JSONArray();
                for (VehicleRoute vr : solve.getRoutes()) {
                    List<Point> vehroute = new ArrayList<>();
                    vehroute.add(new Point(vr.getStart().getLocation().getCoordinate(),0,""));
                    for (TourActivity ta : vr.getActivities()) {
                        vehroute.add(new Point(ta.getLocation().getCoordinate(),0,""));
                    }
                    vehroute.add(new Point(vr.getEnd().getLocation().getCoordinate(),0,""));
                    JSONArray tcoords = new JSONArray();
                    for (int i = 0; i < vehroute.size() - 1; i++) {
                        if (vehroute.get(i).toString().equals(vehroute.get(i + 1).toString())) {
                            continue;
                        }
                        PathWrapper grp = Solver.getRoute(vehroute.get(i), vehroute.get(i + 1), gw);
                        if (grp != null) {
                            for (int j = 0; j < grp.getPoints().size(); j++) {
                                tcoords.put((new JSONArray()).put(grp.getPoints().getLon(j)).put(grp.getPoints().getLat(j)));
                            }
                        }
                    }
                    if (tcoords.length() == 0) {
                        tcoords.put((new JSONArray()).put(vehroute.get(0).x).put(vehroute.get(0).y));
                    }
                    ar.put(tcoords);
                }
                String response = ar.toString();
                he.getResponseHeaders().set("Content-Type", "application/json");
                he.sendResponseHeaders(200, response.length());
                OutputStream os = he.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } catch (JSONException | ParseException | IOException ex) {
                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    public static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        for (String param : query.split("&")) {
            String pair[] = param.split("=");
            if (pair.length > 1) {
                result.put(pair[0], pair[1]);
            } else {
                result.put(pair[0], "");
            }
        }
        return result;
    }

    static class WorkHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            if(mongo != null){
            String trID = null;
            String rfID = null;
            if (httpExchange.getRequestURI().getQuery() != null) {
                Map<String, String> params = queryToMap(httpExchange.getRequestURI().getQuery());
                for (String header : params.keySet()) {
                    if (header.equals("truck")) {
                        trID = java.net.URLDecoder.decode(params.get(header));
                    } else if (header.equals("drivers_RFID")) {
                        rfID = java.net.URLDecoder.decode(params.get(header));
                    }
                    System.out.println(header + "->" + params.get(header));
                }
            }
            if(!lastList.containsKey(trID))
                return;
                DB db = mongo.getDB("orion");	
                DBCollection col = db.getCollection("sgb");
                DBObject query = BasicDBObjectBuilder.start().add("rfid", rfID).get();
		DBCursor cursor = col.find(query);
                JSONArray ar = new JSONArray();
                Double volume = 0.;
		while(cursor.hasNext()){
			System.out.println(cursor.next());
                        if((Integer)cursor.curr().get("time")>DateUtils.truncate(new Date(), Calendar.DATE).getTime()/1000){
                            ar.put(cursor.curr());
                            volume+=(Integer)cursor.curr().get("volume");
                        }
		}                             
                JSONObject ret = new JSONObject();
                ret.put("numBins", lastList.get(trID).size());
                ret.put("volume", volume);
                ret.put("percent", 100.*ar.length()/lastList.get(trID).size());
                String response = ret.toString();
                httpExchange.getResponseHeaders().set("Content-Type", "application/json");
                httpExchange.sendResponseHeaders(200, response.length());
                OutputStream os = httpExchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }
    }

    static class RealHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange httpExchange) {
            JSONArray regs = new JSONArray();
            Double maxTime = -1.;
            if (httpExchange.getRequestURI().getQuery() != null) {
                Map<String, String> params = queryToMap(httpExchange.getRequestURI().getQuery());
                for (String header : params.keySet()) {
                    if (header.equals("regions")) {
                        regs = new JSONArray(java.net.URLDecoder.decode(params.get(header)));
                    } else if (header.equals("max_time_4_garbage_collection")) {
                        maxTime = Double.parseDouble(java.net.URLDecoder.decode(params.get(header)));
                    }
                    System.out.println(header + "->" + params.get(header));
                }
                doWork(httpExchange, false, regs, maxTime);
            } else {
                doWork(httpExchange, false, new JSONArray(), -1.);
            }
        }
    }
    
    static void doWorkGenetic(HttpExchange he, JSONArray regs, Double maxTime) {
        try {
            List<Region> rlist = Region.fromArray(Reader.readArray("get_regions"));
            regionList = new ArrayList<>();
            if (regs.length() == 0) {
                regionList.addAll(rlist);
            } else {
                for (Region r : rlist) {
                    regs.forEach(item -> {
                        if (item.equals(r.id) && !regionList.contains(r)) {
                            regionList.add(r);
                        }
                    });
                }
            }
            List<Truck> tlist = Truck.fromArray(Reader.readArray("get_truck?region=" + regionList.get(0).id));
            trList = new ArrayList<>();
            trList.addAll(tlist);
            trList.sort((Truck o1, Truck o2) -> o2.priority - o1.priority);
            List<SGB> list = SGB.fromArray(Reader.readArray("get_sgb?region=" + regionList.get(0).id));
            sgbList = new ArrayList<>();
            sgbList.addAll(list);
            List<Dump> dlist = Dump.fromArray(Reader.readArray("get_waste_dumps?region=" + regionList.get(0).id));
            dumpList = new ArrayList<>();
            dumpList.addAll(dlist);
            Long t = 0l;
            Integer lproc = Integer.MAX_VALUE;
            List<Polygon> ar = new ArrayList<>();
            int trCount = trList.size();
            do {
                lastList.clear();
                Mark solve = Solver.solve(trList.subList(0, trCount), sgbList, dumpList, gw);
                if(ar.size()> 0 && solve.processed<lproc){
                    break;
                }else{
                    lproc = solve.processed;
                }
                ar = new ArrayList<>();
                List<Long> maxT = new ArrayList<>();
                for (Polygon vr : solve.getRoutes()) {
                    maxT.add(0l);
                    Polygon tcoords = new Polygon();
                    List<String> ls = new ArrayList<>();
                    String trID = null;
                    for (int i = 0; i < vr.size() - 1; i++) {
                        
                        if(vr.get(i).type==2){
                            ls.add(vr.get(i).id);
                        }else if(vr.get(i).type==1){
                            trID = vr.get(i).id;
                        }
                        
                        if (vr.get(i).toString().equals(vr.get(i + 1).toString())) {
                            continue;
                        }
                        PathWrapper grp = Solver.getRoute(vr.get(i), vr.get(i + 1), gw);
                        maxT.set(maxT.size() - 1, maxT.get(maxT.size() - 1) + grp.getTime());
                        if (grp != null) {
                            for (int j = 0; j < grp.getPoints().size(); j++) {
                                tcoords.addPoint(new Point(grp.getPoints().getLon(j), grp.getPoints().getLat(j),0,""));
                            }
                        }
                    }
                    if(trID!=null){
                        lastList.put(trID, ls);
                    }
                    if (tcoords.size() == 0) {
                        tcoords.addPoint(new Point(vr.get(0).x, vr.get(0).y,0,""));
                    }
                    ar.add(tcoords);
                }                
                
                System.out.println(lastList);
                for (Long l : maxT) {
                    if (l > t) {
                        t = l;
                    }
                }
                trCount -= 1;
            } while (t < maxTime * 1000 && trCount > 0);
                String response = GeoJson.getGeoJSON(ar).toString();
                he.getResponseHeaders().set("Content-Type", "application/json");
                he.sendResponseHeaders(200, response.length());
                OutputStream os = he.getResponseBody();
                os.write(response.getBytes());
                os.close();
        } catch (JSONException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ParseException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    static class GeneticHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange httpExchange) {
            JSONArray regs = new JSONArray();
            Double maxTime = -1.;
            if (httpExchange.getRequestURI().getQuery() != null) {
                Map<String, String> params = queryToMap(httpExchange.getRequestURI().getQuery());
                for (String header : params.keySet()) {
                    if (header.equals("regions")) {
                        regs = new JSONArray(java.net.URLDecoder.decode(params.get(header)));
                    } else if (header.equals("max_time_4_garbage_collection")) {
                        maxTime = Double.parseDouble(java.net.URLDecoder.decode(params.get(header)));
                    }
                    System.out.println(header + "->" + params.get(header));
                }
                doWorkGenetic(httpExchange, regs, maxTime);
            } else {
                doWorkGenetic(httpExchange, new JSONArray(), -1.);
            }
        }
    }

}
