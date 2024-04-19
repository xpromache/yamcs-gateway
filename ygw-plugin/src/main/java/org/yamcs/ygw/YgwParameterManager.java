package org.yamcs.ygw;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.yamcs.Processor;
import org.yamcs.YamcsServer;
import org.yamcs.logging.Log;
import org.yamcs.mdb.Mdb;
import org.yamcs.mdb.MdbFactory;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SoftwareParameterManager;
import org.yamcs.parameter.Value;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.time.TimeService;
import org.yamcs.xtce.DataSource;
import org.yamcs.xtce.NameDescription;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.ParameterType;
import org.yamcs.xtce.UnitType;
import org.yamcs.ygw.ParameterPool.YgwParameter;
import org.yamcs.ygw.protobuf.Ygw.ParameterData;
import org.yamcs.ygw.protobuf.Ygw.ParameterDefinition;
import org.yamcs.ygw.protobuf.Ygw.ParameterDefinitionList;

/**
 * Handles parameter updates from the clients.
 * <p>
 * When a YgwLink is configured, one instance of this is created and registered to the realtime processor of the Yamcs
 * instance where the link belongs.
 * <p>
 * Objects of this class are linked to the processor so they may be used by multiple gateways. Each gateway should
 * configure their own namespace to avoid clashing of parameters.
 */
public class YgwParameterManager implements SoftwareParameterManager {
    static final Log log = new Log(YgwParameterManager.class);

    final ParameterPool pool = new ParameterPool();

    final DataSource dataSource;
    final Mdb mdb;
    final TimeService timeService;

    public YgwParameterManager(Processor processor, String yamcsInstance, DataSource dataSource) {
        this.dataSource = dataSource;
        this.mdb = MdbFactory.getInstance(yamcsInstance);
        this.timeService = YamcsServer.getTimeService(yamcsInstance);
    }

    // called from Yamcs API -> send all parameter values to YGW
    @Override
    public void updateParameters(List<ParameterValue> pvals) {
        // TODO Auto-generated method stub

    }

    // called from Yamcs API -> send all parameter values to YGW
    @Override
    public void updateParameter(Parameter p, Value v) {
        // TODO Auto-generated method stub

    }

    // called when parameters are coming from the gateway
    public List<ParameterValue> processParameters(YgwLink ygwLink, int nodeId,
            ParameterData pdata) {
        List<ParameterValue> plist = new ArrayList<>(pdata.getParameters().length());
        for (var qpv : pdata.getParameters()) {
            YgwParameter ygwp = pool.getById(ygwLink, nodeId, qpv.getId());
            if(ygwp == null) {
                log.warn("No parameter found for linke: {}, node: {}, pid: {}; ignoring", ygwLink.getName(), nodeId, qpv.getId());
                continue;
            }
            plist.add(fromProto(ygwp, qpv));

        }
        return plist;

    }


    public void addParameterDefs(YgwLink link, int nodeId, String namespace, ParameterDefinitionList pdefs) {
        List<Parameter> plist = new ArrayList<>();
        List<YgwParameter> ygwPlist = new ArrayList<>();
        
        for (ParameterDefinition pdef : pdefs.getDefinitions()) {
            if (pdef.getRelativeName().contains("..")) {
                log.warn("Invalid name {} for parameter, ignored", pdef.getRelativeName());
                continue;
            }
            String fqn = namespace + NameDescription.PATH_SEPARATOR + pdef.getRelativeName();
            if (mdb.getParameter(fqn) != null) {
                log.debug("Parameter {} already exists in the MDB, not adding it", fqn);
                continue;
            }

            ParameterType ptype = null;
            try {
                ptype = getParameterType(namespace, pdef);
            } catch (IOException e) {
                log.error("Error adding parameters to the MDB", e);
                continue;
            }

            if (ptype == null) {
                log.warn("Parameter type {} is not basic and could not be found in the MDB; parameter ignored",
                        pdef.getPtype());
                continue;
            }
            String name = NameDescription.getName(fqn);
            Parameter p = new Parameter(name);
            if (pdef.hasDescription()) {
                p.setShortDescription(pdef.getDescription());
            }
            p.setQualifiedName(fqn);
            p.setDataSource(dataSource);

            p.setParameterType(ptype);
            plist.add(p);
            ygwPlist.add(new YgwParameter(link, nodeId, p, pdef.getId()));
        }

        try {
            mdb.addParameters(plist, true, false);
        } catch (IOException | IllegalArgumentException e) {
            log.error("Error adding parameters to the MDB", e);
            return;
        }
        pool.add(link, ygwPlist);
    }

    private ParameterType getParameterType(String namespace, ParameterDefinition pdef) throws IOException {
        var unit = getUnit(pdef);
        Type basicType = getBasicType(pdef.getPtype());
        if (basicType == null) {
            // not a basic type, it must exist in the MDB
            return mdb.getParameterType(pdef.getPtype());
        } else {
            return mdb.getOrCreateBasicType(namespace, basicType, unit);
        }
    }

    private UnitType getUnit(ParameterDefinition pdef) {
        if (pdef.hasUnit()) {
            return new UnitType(pdef.getUnit());
        } else {
            return null;
        }
    }

    // get the basic type (sint32, string, etc) corresponding to the string
    private Type getBasicType(String pt) {
        try {
            Type t = Type.valueOf(pt.toUpperCase());
            if (t == Type.AGGREGATE || t == Type.ARRAY || t == Type.NONE) {
                return null;
            } else {
                return t;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ParameterValue fromProto(YgwParameter ygwp, org.yamcs.ygw.protobuf.Ygw.ParameterValue qpv) {
        ParameterValue pv = new ParameterValue(ygwp.p);
        if (qpv.hasEngValue()) {
            pv.setEngValue(ProtoConverter.fromProto(qpv.getEngValue()));
        }

        if (qpv.hasRawValue()) {
            pv.setRawValue(ProtoConverter.fromProto(qpv.getRawValue()));
        }

        if (qpv.hasGenerationTime()) {
            pv.setGenerationTime(ProtoConverter.fromProtoMillis(qpv.getGenerationTime()));
        } else {
            pv.setGenerationTime(timeService.getMissionTime());
        }

        if (qpv.hasAcquisitionTime()) {
            pv.setAcquisitionTime(ProtoConverter.fromProtoMillis(qpv.getAcquisitionTime()));
        } else {
            pv.setAcquisitionTime(timeService.getMissionTime());
        }

        if (qpv.hasExpireMillis()) {
            pv.setExpireMillis(qpv.getExpireMillis());
        }

        return pv;
    }


}
