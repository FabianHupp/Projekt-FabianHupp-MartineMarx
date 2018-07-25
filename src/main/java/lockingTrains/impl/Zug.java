package lockingTrains.impl;

import lockingTrains.shared.Connection;
import lockingTrains.shared.Location;
import lockingTrains.shared.Map;
import lockingTrains.shared.TrainSchedule;
import lockingTrains.validation.Recorder;

import java.util.ArrayList;
import java.util.List;

public class Zug implements Runnable{

    private TrainSchedule schedule;
    private int id;
    private Map map;
    private FahrtdienstLeitung FdL;
    private Location destination;
    private Location act_position;
    private Recorder rec;

    Zug (TrainSchedule sched, int i, Map m, FahrtdienstLeitung fahrtdl, Recorder recorder){
        this.schedule = sched;
        this.id = i;
        this.map = m;
        this.FdL = fahrtdl;
        this.destination = sched.destination();
        this.act_position = sched.origin();
        rec = recorder;
    }


    @Override
    public void run() {

        //trainschedule startet:
        rec.start(schedule);

        List<Connection> empty_avoid_list = new ArrayList<>();

        while(!act_position.equals(destination)){
            List<Connection> route = map.route(act_position, destination, empty_avoid_list);

            //falls keine Route existiert obwohl leere avoid-Liste => fail
            if(route == null){
                return;
            }

            //wenn die route leer ist, dann finished, kein arrive event weil man ja schon vorher angekommen ist;
            // return beendet thread
            if(route.isEmpty()){
                    FdL.isFinished();
                    rec.finish(schedule);
                    return;
            }
            //wenn die route nicht null und nicht leer ist, gibt es eine reservierbare route
            List<Connection> avoid = tryReserveRoute(route);
            if(avoid.isEmpty()){
                    //wenn man die route reservieren konnte dann darf man fahren
                    drive(route);
                    //hier meldung machen dass man finished ist
                    FdL.isFinished();
                    rec.finish(schedule);
                    return;
            }

            //reservieren hat nicht geklappt => nochmal veruschen mit gleisen in der avoid-liste
            if(!avoid.isEmpty()){
                route = map.route(act_position,destination,avoid);
            }
            //falls die neue route existiert
            if(route != null){
                //falls sie leer ist
                if(route.isEmpty()){
                    FdL.isFinished();
                    rec.finish(schedule);
                    return;
                }else{
                    //falls sie nicht leer ist
                    avoid = tryReserveRoute(route);
                    if(avoid.isEmpty()){
                        drive(route);
                        FdL.isFinished();
                        rec.finish(schedule);
                        return;
                    }
                }


            }
            //wenn keine route mit avoids gefunden wurde und/oder die neue strecke beim reservieren wieder neue avoids geliefert hat:
            //gehe zu Schritt 3: nächsten Bahnhof finden und warten
            if(route == null || !avoid.isEmpty()){

            }


        }

    }


    /**
     * Versucht die gegebene Route in der allgemeinen totalen Ordnung zu reservieren.
     * Falls beim Reservieren ein Streckenteil nach der totalen Ordnung schon reserviert ist, dann gib die bereits reservierten
     * Teile wieder frei.
     * @param route route to be reserved
     * @return List<Connection> - leer wenn ganze route reserviert wurde, gefüllt wenn nicht der fall und mit connections
     * die avoided werden sollen
     */
    private List<Connection> tryReserveRoute(List<Connection> route){
        List<Connection> copy_route = copy(route);
        List<Connection> save_reserved = new ArrayList<>();
        List<Connection> avoid = new ArrayList<>();

        while(!copy_route.isEmpty()){
            int list_id = 0;                            //eindeutige position in der Liste
            int smallestId = copy_route.get(0).id();    //niedrigste id der Gleise
            for(Connection c : copy_route){
                if(c.id() < smallestId){
                    smallestId = c.id();
                    list_id = copy_route.indexOf(c);
                }
            }

            //Gleis reservieren, falls es failt dann Connection in avoid einfügen
            boolean reserved = this.FdL.lockGleis(smallestId, this.id);
            if(reserved){
                save_reserved.add(copy_route.get(list_id));
            }else{
                avoid.add(copy_route.get(list_id));
                reverse_reservation(save_reserved);
                return avoid;
            }
            //Stellplätze reservieren
            boolean reserved_place_first =  this.FdL.ReservePlace(copy_route.get(list_id).first().id(),this.id);
            if(!reserved_place_first){
                avoid.add(copy_route.get(list_id));
                reverse_reservation(save_reserved);
                return avoid;
            }
            boolean reserved_place_second =  this.FdL.ReservePlace(copy_route.get(list_id).second().id(),this.id);
            if(!reserved_place_second){
                avoid.add(copy_route.get(list_id));
                reverse_reservation(save_reserved);
                return avoid;
            }

            //wenn allerdings nichts gefailed ist, dann das element aus copy_route entfernen, und weiter mit nächstem gleis
            copy_route.remove(list_id);
        }

        return avoid;
    }

    /**
     * Macht die Reservierung eines Zuges bis zu einer bestimmten Stelle Rückgängig wenn nicht die ganze Strecke reservierbar ist
     * @param save_reserved connections to be reversed
     */
    private void reverse_reservation(List<Connection> save_reserved) {
        for(Connection c : save_reserved){
            FdL.UnlockGleis(c.id(),this.id);
            FdL.FreePlace(c.first().id(),this.id);
            FdL.FreePlace(c.second().id(),this.id);
        }
    }


    /**
     * Wenn die Route reserviert wurde, dann wird hier "gefahren" und Gleise und alle Zwischenstops freigegeben.
     * @param route route to be driven
     */
    private void drive(List<Connection> route){
        List<Connection> copy_route = copy(route);
        while(!copy_route.isEmpty()){
            int next_gleis = -1;            //id in liste
            int unique_gleis_id = -1;       //unique id overall

            //finde das erste Gleis dass gefahren wird
            for(Connection c: copy_route){
                if(c.first() == act_position || c.second() == act_position){
                    next_gleis = copy_route.indexOf(c);
                    unique_gleis_id = c.id();
                }
            }


            //find out if first or second are the act_position
            Location destination;
            if(copy_route.get(next_gleis).first().equals(act_position)){
                destination = copy_route.get(next_gleis).second();
            }else{
                destination = copy_route.get(next_gleis).first();
            }

            //unlocke den ersten Platz und fahre los
            FdL.FreePlace(act_position.id(),id);
            //recorder melden dass man jetzt leaved
            rec.leave(schedule,act_position);

            //travel anmelden und losfahren
            rec.travel(schedule,copy_route.get(next_gleis));
            try {
                copy_route.get(next_gleis).travel();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            //Gleis freigeben und act_position ändern
            rec.arrive(schedule,destination);
            FdL.UnlockGleis(unique_gleis_id,id);
            act_position = destination;

            //gefahrenes Gleis aus der Liste entfernen
            copy_route.remove(next_gleis);
        }
    }

    private List<Connection> copy(List<Connection> route){
        List<Connection> copy_route = new ArrayList<>();
        for(Connection c: route){
            copy_route.add(c);
        }
        return copy_route;
    }


}
