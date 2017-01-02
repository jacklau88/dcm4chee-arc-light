/*
 * *** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2013
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.hl7.psu;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.hl7.HL7Message;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Device;
import org.dcm4chee.arc.conf.ArchiveAEExtension;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.conf.Duration;
import org.dcm4chee.arc.entity.HL7PSUTask;
import org.dcm4chee.arc.entity.MPPS;
import org.dcm4chee.arc.mpps.MPPSContext;
import org.dcm4chee.arc.query.QueryService;
import org.dcm4chee.arc.store.StoreContext;
import org.dcm4chee.arc.store.StoreSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dcm4chee.arc.Scheduler;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @since Jan 2017
 */
@ApplicationScoped
public class HL7PSUScheduler extends Scheduler {
    private static final Logger LOG = LoggerFactory.getLogger(HL7PSUScheduler.class);

    @Inject
    private Device device;

    @Inject
    private HL7ProcedureStatusUpdateEJB ejb;

    @Inject
    private QueryService queryService;

    protected HL7PSUScheduler() {
        super(Mode.scheduleWithFixedDelay);
    }

    @Override
    protected Logger log() {
        return LOG;
    }

    @Override
    protected Duration getPollingInterval() {
        ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
        return arcDev.getHl7psuTaskPollingInterval();
    }

    @Override
    protected void execute() {
        ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
        int fetchSize = arcDev.getHl7psuTaskFetchSize();
        long hl7psuTaskPk = 0;
        List<HL7PSUTask> hl7psuTasks;
        do {
            hl7psuTasks = ejb.fetchHL7PSUTasksForMPPS(device.getDeviceName(), hl7psuTaskPk, fetchSize);
            for (HL7PSUTask hl7psuTask : hl7psuTasks)
                try {
                    hl7psuTaskPk = hl7psuTask.getPk();
                    ApplicationEntity ae = device.getApplicationEntity(hl7psuTask.getCalledAET(), true);
                    ArchiveAEExtension arcAE = ae.getAEExtension(ArchiveAEExtension.class);
                    LOG.info("Check availability of {}", hl7psuTask.getMpps());
                    if (checkAllRefInMpps(ae, hl7psuTask.getMpps())) {
                        List<HL7Message> hl7msgs = createHL7Msg(hl7psuTask, arcAE);
                        for (HL7Message hl7msg : hl7msgs) {
                            LOG.info("Schedule {}", hl7psuTask);
                            ejb.scheduleHL7PSUTask(hl7psuTask, hl7msg);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to process {}", hl7psuTask, e);
                }
        } while (hl7psuTasks.size() == fetchSize);
        do {
            hl7psuTasks = ejb.fetchHL7PSUTasksForStudy(device.getDeviceName(), fetchSize);
            for (HL7PSUTask hl7psuTask : hl7psuTasks)
                try {
                    ApplicationEntity ae = device.getApplicationEntity(hl7psuTask.getCalledAET(), true);
                    ArchiveAEExtension arcAE = ae.getAEExtension(ArchiveAEExtension.class);
                    List<HL7Message> hl7msgs = createHL7Msg(hl7psuTask, arcAE);
                    if (hl7psuTask.getMpps() == null) {
                        LOG.info("Schedule {}", hl7psuTask);
                        for (HL7Message hl7msg : hl7msgs) {
                            ejb.scheduleHL7PSUTask(hl7psuTask, hl7msg);
                        }
                    } else {
                        if (arcAE.hl7psuOnTimeout() && !hl7msgs.isEmpty()) {
                            for (HL7Message hl7msg : hl7msgs) {
                                LOG.warn("Timeout for {} exceeded - schedule HL7 Procedure Status Update for available instances", hl7psuTask);
                                ejb.scheduleHL7PSUTask(hl7psuTask, hl7msg);
                            }
                        } else {
                            LOG.warn("Timeout for {} exceeded - no HL7 Procedure Status Update task.", hl7psuTask);
                            ejb.removeHL7PSUTask(hl7psuTask);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to process {}", hl7psuTask, e);
                }
        } while (hl7psuTasks.size() == fetchSize);
    }

    public void onStore(@Observes StoreContext ctx) {
        if (ctx.getLocations().isEmpty())
            return;

        StoreSession session = ctx.getStoreSession();
        ArchiveAEExtension arcAE = session.getArchiveAEExtension();
        String[] hl7psuDestinations = arcAE.hl7psuDestinations();
        Duration hl7psuDelay = arcAE.hl7psuDelay();
        if (hl7psuDestinations.length != 0 && hl7psuDelay != null) {
            try {
                ejb.createOrUpdateHL7PSUTaskForStudy(arcAE, ctx);
            } catch (Exception e) {
                LOG.warn("{}: Failed to create or update HL7PSUTask", ctx, e);
            }
        }
    }

    void onMPPSReceive(@Observes MPPSContext ctx) {
        if (ctx.getMPPS().getStatus() == MPPS.Status.COMPLETED) {
            ArchiveAEExtension arcAE = ctx.getArchiveAEExtension();
            String[] hl7psuDestinations = arcAE.hl7psuDestinations();
            if (hl7psuDestinations.length != 0 && arcAE.hl7psuDelay() == null) {
                try {
                    ejb.createHL7PSUTaskForMPPS(arcAE, ctx);
                } catch (Exception e) {
                    LOG.warn("{}: Failed to create HL7 Procedure Status Update Task", ctx, e);
                }
            }
        }
    }

    private boolean checkAllRefInMpps(ApplicationEntity ae, MPPS mpps) {
        Attributes mppsAttrs = mpps.getAttributes();
        String studyInstanceUID = mpps.getStudyInstanceUID();
        Sequence perfSeriesSeq = mppsAttrs.getSequence(Tag.PerformedSeriesSequence);
        for (Attributes perfSeries : perfSeriesSeq) {
            String seriesInstanceUID = perfSeries.getString(Tag.SeriesInstanceUID);
            Attributes ianForSeries = queryService.createIAN(ae, studyInstanceUID, seriesInstanceUID, null);
            if (ianForSeries == null)
                return false;
            Attributes refStudy = ianForSeries.getNestedDataset(Tag.CurrentRequestedProcedureEvidenceSequence);
            Attributes refSeries = refStudy.getSequence(Tag.ReferencedSeriesSequence).remove(0);
            Sequence available = refSeries.getSequence(Tag.ReferencedSOPSequence);
            if (!allAvailable(perfSeries.getSequence(Tag.ReferencedImageSequence), available) ||
                    !allAvailable(perfSeries.getSequence(Tag.ReferencedNonImageCompositeSOPInstanceSequence), available))
                return false;
        }
        return true;
    }

    private boolean allAvailable(Sequence performed, Sequence available) {
        if (performed != null)
            for (Attributes ref : performed) {
                if (!available(ref, available))
                    return false;
            }
        return true;
    }

    private boolean available(Attributes performed, Sequence available) {
        String iuid = performed.getString(Tag.ReferencedSOPInstanceUID);
        for (Attributes ref : available) {
            if (iuid.equals(ref.getString(Tag.ReferencedSOPInstanceUID)))
                return true;
        }
        return false;
    }

    private List<HL7Message> createHL7Msg(HL7PSUTask hl7psuTask, ArchiveAEExtension arcAE) {
        List<HL7Message> hl7msgs = new ArrayList<>();
        for (String dest : hl7psuTask.getHl7psuDestinations())
            hl7msgs.add(BuildHL7Message.buildHL7Mesg(hl7psuTask, dest, arcAE));
        return hl7msgs;
    }
}
