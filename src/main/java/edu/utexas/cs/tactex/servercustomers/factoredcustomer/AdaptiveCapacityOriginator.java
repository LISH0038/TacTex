/*
 * TacTex - a power trading agent that competed in the Power Trading Agent Competition (Power TAC) www.powertac.org
 * Copyright (c) 2013-2016 Daniel Urieli and Peter Stone {urieli,pstone}@cs.utexas.edu               
 *
 *
 * This file is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This file is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * This file incorporates work covered by the following copyright and  
 * permission notice:  
 *
*     Copyright 2011 the original author or authors.
*
*     Licensed under the Apache License, Version 2.0 (the "License");
*     you may not use this file except in compliance with the License.
*     You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
*     Unless required by applicable law or agreed to in writing, software
*     distributed under the License is distributed on an
*     "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
*     either express or implied. See the License for the specific language
*     governing permissions and limitations under the License.
*/

package edu.utexas.cs.tactex.servercustomers.factoredcustomer;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.log4j.Logger;
import org.joda.time.DateTimeZone;
import org.powertac.common.Tariff;
//import org.powertac.common.ConfigServerBroker;
import org.powertac.common.TariffSpecification;
import org.powertac.common.Timeslot;
import org.powertac.common.state.Domain;

import edu.utexas.cs.tactex.servercustomers.common.TariffSubscription;
import edu.utexas.cs.tactex.servercustomers.factoredcustomer.ProfileOptimizerStructure.ProfileSelectionMethod;
import edu.utexas.cs.tactex.servercustomers.factoredcustomer.ProfileRecommendation.Opinion;
import edu.utexas.cs.tactex.servercustomers.factoredcustomer.ProfileRecommendation.ScoringFactor;
import edu.utexas.cs.tactex.servercustomers.factoredcustomer.utils.SeedIdGenerator;

/**
 * Extends @code{DefaultCapacityOriginator} to adapt to the learning behavior 
 * of @code{LearningUtilityOptimizer}.
 * 
 * @author Prashant Reddy
 */
@Domain
final class AdaptiveCapacityOriginator extends DefaultCapacityOriginator
     implements ProfileRecommendation.Listener
{    
    //private RandomSeedRepo randomSeedRepo;
    
    private final ProfileOptimizerStructure optimizerStructure;

    private final Random recommendationHandler;

    private Map<TariffSubscription, Map<Integer, Double>> forecastCapacitiesPerSub;

    private Map<Tariff, Double> tariff2inconv;
    
    
    
    AdaptiveCapacityOriginator(FactoredCustomerService service,
                               CapacityStructure capacityStructure,
                               DefaultCapacityBundle bundle) 
    {
        super(service, capacityStructure, bundle);
        log = Logger.getLogger(AdaptiveCapacityOriginator.class.getName());
        
        //randomSeedRepo = (RandomSeedRepo) SpringApplicationContext.getBean("randomSeedRepo");

        optimizerStructure = getParentBundle().getOptimizerStructure();

        recommendationHandler =
                new Random(service.getRandomSeedRepo()
                           .getRandomSeed("factoredcustomer.AdaptiveCapacityOriginator", 
                                          SeedIdGenerator.getId(),
                                          "RecommendationHandler")
                                          .getValue());
        
        forecastCapacitiesPerSub = new HashMap<TariffSubscription, Map<Integer,Double>>();
        tariff2inconv = new HashMap<Tariff, Double>();
    }
    
    @Override /** @code{ProfileRecommendation.Listener} **/
    public void handleProfileRecommendation(ProfileRecommendation globalRec, int currentTimeslot)
    {        
        //log.info("handleProfileRecommendation()");
        double draw1 = recommendationHandler.nextFloat();
        if (draw1 > optimizerStructure.reactivityFactor) {
            log.debug(logIdentifier + ": Ignoring received profile recommendation");
            return;
        }
        
        ProfileRecommendation localRec;
        double draw2 = recommendationHandler.nextFloat();
        if (draw2 < optimizerStructure.receptivityFactor) {
            log.debug(logIdentifier + ": Adopting profile recommendation as received");
            localRec = globalRec;
        }
        else {
            //log.info("getting opinions");
            localRec = new ProfileRecommendation(globalRec.getOpinions());
            
            Map<ScoringFactor, Double> weights = new HashMap<ScoringFactor, Double>();
            weights.put(ScoringFactor.PROFILE_CHANGE, optimizerStructure.profileChangeWeight);
            weights.put(ScoringFactor.BUNDLE_VALUE, optimizerStructure.bundleValueWeight);
            localRec.computeScores(weights);
            
            localRec.computeUtilities();
            localRec.computeProbabilities(optimizerStructure.rationalityFactor);
        }
        CapacityProfile chosenProfile;
        if (optimizerStructure.profileSelectionMethod == ProfileSelectionMethod.BEST_UTILITY) {
            chosenProfile = selectBestProfileInRecommendation(localRec);        
        } else { // LOGIT_CHOICE 
            chosenProfile = drawProfileFromRecommendation(localRec);        
        }
        //log.info("chosen: ALL-AVG  " + chosenProfile.toString());
        overwriteForecastCapacities(service.getTimeslotRepo().findBySerialNumber(currentTimeslot),//service.getTimeslotRepo().currentTimeslot(),
                                    chosenProfile);
    }

    @Override /** @code{ProfileRecommendation.Listener} **/
    public void handleProfileRecommendationPerSub(ProfileRecommendation globalRec, TariffSubscription sub, int currentTimeslot, CapacityProfile capacityProfile)
    {        
        //log.info("handleProfileRecommendationPerSub()");
        double draw1 = recommendationHandler.nextFloat();
        if (draw1 > optimizerStructure.reactivityFactor) {
            log.debug(logIdentifier + ": Ignoring received profile recommendation");
            return;
        }
        
        ProfileRecommendation localRec;
        double draw2 = recommendationHandler.nextFloat();
        if (draw2 < optimizerStructure.receptivityFactor) {
            log.debug(logIdentifier + ": Adopting profile recommendation as received");
            localRec = globalRec;
        }
        else {
            //log.info("getting opinions");
            localRec = new ProfileRecommendation(globalRec.getOpinions());
            
            Map<ScoringFactor, Double> weights = new HashMap<ScoringFactor, Double>();
            weights.put(ScoringFactor.PROFILE_CHANGE, optimizerStructure.profileChangeWeight);
            weights.put(ScoringFactor.BUNDLE_VALUE, optimizerStructure.bundleValueWeight);
            localRec.computeScores(weights);
            
            localRec.computeUtilities();
            localRec.computeProbabilities(optimizerStructure.rationalityFactor);
        }
        CapacityProfile chosenProfile;
        if (optimizerStructure.profileSelectionMethod == ProfileSelectionMethod.BEST_UTILITY) {
            chosenProfile = selectBestProfileInRecommendation(localRec);        
        } else { // LOGIT_CHOICE 
            chosenProfile = drawProfileFromRecommendation(localRec);        
        }
        if (!chosenProfile.toString().equals(capacityProfile.toString())) {
          //log.info("handleProfileRecommendationPerSub(" + sub.getCustomer().getName() + ", " + sub.getTariff().getId() + ") DIFFERENT:");
          //log.info("forecast: " + capacityProfile.toString());
        }
        else {
          //log.info("handleProfileRecommendationPerSub(" + sub.getCustomer().getName() + ", " + sub.getTariff().getId() + ") SAME:");
        }
        //log.info("srv chosen: " + sub.getCustomer().getName() + " " +  sub.getTariff().getId() + " " + chosenProfile.toString());
        overwriteForecastCapacitiesPerSub(service.getTimeslotRepo().findBySerialNumber(currentTimeslot),//service.getTimeslotRepo().currentTimeslot(),
                                    chosenProfile, sub);
        // record inconv
        // (non-scaled) score = (charge / a) + w x d(e,e') / b 
        // so a x score is supposed to be comparable to profile charge, taking inconv into account 
        Opinion opinionOnChosenProfile = localRec.getOpinions().get(chosenProfile);
        double originalScore = localRec.getNonScaledScore(chosenProfile);
        // a = charge / normalized-charge
        double costNormalizationConst = (opinionOnChosenProfile.normUsageCharge != 0) ? opinionOnChosenProfile.usageCharge / opinionOnChosenProfile.normUsageCharge : 0;
        // scaled-inconv-factor = |a| x score - charge  = w|a|/b x d(e,e')
        double inconvenienceFactor = Math.abs(costNormalizationConst) * originalScore - opinionOnChosenProfile.usageCharge;
        tariff2inconv.put(sub.getTariff(), inconvenienceFactor);
    }
    private CapacityProfile selectBestProfileInRecommendation(ProfileRecommendation rec) 
    {
        //log.info("selectBestProfileInRecommendation()");
        double bestUtility = Double.MIN_VALUE;
        CapacityProfile bestProfile = null;
        for (AbstractMap.Entry<CapacityProfile, Double> entry: rec.getUtilities().entrySet()) {
            if (entry.getValue() > bestUtility) {
                bestUtility = entry.getValue();
                bestProfile = entry.getKey();
            }
        }        
        if (bestProfile == null) throw new Error("Best profile in recommendation is null!");
        //log.info("selectBestProfileInRecommendation() " + bestProfile.toString());
        return bestProfile;
    }
    
    private CapacityProfile drawProfileFromRecommendation(ProfileRecommendation rec) 
    {
        
        double draw = recommendationHandler.nextFloat();
        //log.info("drawProfileFromRecommendation(): " + draw);
        // sort map entries, for reproducability        
        ArrayList<Map.Entry<CapacityProfile, Double>> l = new ArrayList<Entry<CapacityProfile, Double>>(rec.getProbabilities().entrySet());
        Collections.sort(l, new Comparator<Map.Entry<CapacityProfile, Double>>(){
          public int compare(Map.Entry<CapacityProfile, Double> o1, Map.Entry<CapacityProfile, Double> o2) {
             return o1.getValue().compareTo(o2.getValue());
         }});
        //log.info("sorted entries: " + l);
        // use the sorted map and the draw to sample an entry 
        double sumProb = 0.0;
        for (AbstractMap.Entry<CapacityProfile, Double> entry: l) {
            sumProb += entry.getValue();
            if (draw < sumProb) {
                return entry.getKey();
            }
        }        
        throw new Error("Drawing from recommendation resulted in a null profile!");
    }
    
    private void overwriteForecastCapacities(Timeslot timeslot, CapacityProfile profile)
    {
        //log.info("overwriteForecastCapacities()");
        Timeslot slider = timeslot;
        for (int i=0; i < CapacityProfile.NUM_TIMESLOTS; ++i) {
            //log.info("forecastCapacities.put(" + slider.getSerialNumber() + "," + profile.getCapacity(i) + ")");
            forecastCapacities.put(slider.getSerialNumber(), profile.getCapacity(i));
            slider = service.getTimeslotRepo().getNext(slider);
        }
    }
    
    private void overwriteForecastCapacitiesPerSub(Timeslot timeslot, CapacityProfile profile, TariffSubscription sub)
    {
        //log.info("overwriteForecastCapacitiesPerSub()");
        Timeslot slider = timeslot;
        for (int i=0; i < CapacityProfile.NUM_TIMESLOTS; ++i) {
            //log.info("forecastCapacitiesPerSub.put(" + sub.getCustomer().getName() + " " + sub.getTariff().getId() + " " + slider.getSerialNumber() + "," + profile.getCapacity(i) + ")");
            int futureTimeslot = slider.getSerialNumber();
            double futureCapacity = profile.getCapacity(i);
            insertIntoForecastCapacitiesPerSub(sub, futureTimeslot, futureCapacity);
            slider = service.getTimeslotRepo().getNext(slider);
        }
    }

    private void insertIntoForecastCapacitiesPerSub(TariffSubscription sub,
        int futureTimeslot, double futureCapacity) {
      Map<Integer, Double> ts2capacity = forecastCapacitiesPerSub.get(sub);
      if (null == ts2capacity) {
        ts2capacity = new HashMap<Integer, Double>();
        forecastCapacitiesPerSub.put(sub, ts2capacity);        
      }
      //log.info("forecastCapacitiesPerSub[" + sub.getTariff().getId() + "," + futureTimeslot + "]=" + futureCapacity);
      ts2capacity.put(futureTimeslot, futureCapacity);
    }
    
    @Override
    public double useCapacity(TariffSubscription subscription)
    {
        //log.info("useCapacity()");
        int timeslot = service.getTimeslotRepo().currentSerialNumber();
        
        // we don't re-adjust for current weather here; would not be accurate for wind/solar production
        // 
        // : try to get per sub first, if doesn't work get the 
        // old, averaged one
        double forecastCapacity = getForecastCapacityPerSub(timeslot, timeslot, subscription);
        //if (null == forecastCapacity) {
        //  forecastCapacity = getForecastCapacity(timeslot);
        //  //log.info(": failed to get sub capacity!");
        //}
        //else {
        //  // TODO: remove, just for print
        //  double perAllCapacity = getForecastCapacity(timeslot);
        //  //log.info(": succeeded to get sub capacity!, " + ((forecastCapacity != perAllCapacity) ? "DIFFERENT" : "") + " normalized: " + forecastCapacity/subscription.getCustomersCommitted() + " instead of " + perAllCapacity/subscription.getCustomersCommitted() + " " + subscription.getTariff().getBroker().getUsername() + " time=" + service.getTimeslotRepo().getTimeForIndex(timeslot).toDateTime().getHourOfDay());
        //}
//        logCapacityDetails(logIdentifier + ": Forecast capacity being used for timeslot " 
//                           + timeslot + " = " + forecastCapacity);        
        //log.info(logIdentifier + ": srv " + subscription.getCustomer().getName() + " " + subscription.getTariff().getId() + " Forecast capacity being used for timeslot " 
        //                   + timeslot + " = " + forecastCapacity + " instead of forecastCapacities(" + timeslot + ")=" + getForecastCapacity(timeslot));

        double adjustedCapacity = forecastCapacity;       
        adjustedCapacity = adjustCapacityForSubscription(timeslot, adjustedCapacity, subscription);
        //log.info("Adjusted capacity 1: " + adjustedCapacity);
        if (Double.isNaN(adjustedCapacity)) {
            throw new Error("Adjusted capacity is NaN for forecast capacity = " + forecastCapacity);
        }
        
        adjustedCapacity = truncateTo2Decimals(adjustedCapacity);
        actualCapacities.put(timeslot, adjustedCapacity);        
        //log.info(logIdentifier + ": Adjusted capacity for tariff " + subscription.getTariff().getId() + " = " + adjustedCapacity);        
        return adjustedCapacity;
    }
    
    // : data access methods
    @Override
    public ArrayRealVector getPredictedEnergy(TariffSubscription subscription,
        int recordLength, int currentTimeslot) throws Exception {
      CapacityProfile predictedEnergyProfile = getForecastPerSubStartingAt(currentTimeslot, currentTimeslot, subscription);
      // elasticity
      //if (ConfigServerBroker.useElasticity()) {
        predictedEnergyProfile = adjustCapacityProfileForTariffRates(predictedEnergyProfile, currentTimeslot, subscription);
      //}
      //log.info("adaptivecaporig " + Arrays.toString(predictedEnergyProfile.values.toArray()));
      return convertEnergyProfileFromServerToBroker(predictedEnergyProfile, recordLength);
    }
    
    @Override
    public double getShiftingInconvenienceFactor(Tariff tariff, int recordLength) {
      Double inconv = tariff2inconv.get(tariff);
      // shouldn't happen
      if (inconv != null) {
        // scale from 24 hours to a week (constant x sum-squared-errors is additive)
        int scale = recordLength / CapacityProfile.NUM_TIMESLOTS;
        return inconv * scale;
      }
      log.error("How come inconvenience is null?");
      return 0;
    }

    @Override
    public void clearSubscriptionRelatedData() {
      forecastCapacitiesPerSub.clear();
    }

    @Override
    public CapacityProfile getCurrentForecastPerSub(int currentTimeslot, TariffSubscription sub) {
      return getForecastPerSubStartingAt(currentTimeslot, currentTimeslot, sub);
    }

    private CapacityProfile getForecastPerSubStartingAt(int currentTimeslot, 
        int startingTimeslot,
        TariffSubscription subscription) {
      int timeslot = startingTimeslot;
      List<Double> values = new ArrayList<Double>();
      for (int i = 0; i < CapacityProfile.NUM_TIMESLOTS; ++i) {
        values.add(getForecastCapacityPerSub(currentTimeslot, timeslot, subscription));
        timeslot += 1;
      }
      return new CapacityProfile(values);
    }

    private Double getForecastCapacityPerSub(int currentTimeslot, 
        int timeslot,
        TariffSubscription subscription) {

      Map<Integer, Double> ts2capacity = forecastCapacitiesPerSub.get(subscription);
      
      if (null == ts2capacity || null == ts2capacity.get(timeslot)) {
        //log.info(": failed to get sub capacity! falling back to default...");
        return getForecastCapacity(currentTimeslot, timeslot);
      } else {
        double perAllCapacity = getForecastCapacity(currentTimeslot, timeslot);
        double perSubCapacity = ts2capacity.get(timeslot);
        //log.info(": succeeded to get sub capacity, " + ((perSubCapacity != perAllCapacity) ? "DIFFERENT " : " ") + "nonShifted=" + perAllCapacity + " shifted=" + perSubCapacity + " " + subscription.getTariff().getBroker().getUsername() + " time=" + service.getTimeslotRepo().getTimeForIndex(timeslot).toDateTime(DateTimeZone.UTC).getHourOfDay());
        //log.debug("sub capacity for " + subscription.getCustomer().getName() + " " + subscription.getTariff().getId() + ": " + perSubCapacity);
        return perSubCapacity; 
      }
    } 
    
} // end class



