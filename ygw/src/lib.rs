pub mod msg;
pub mod ygw_server;

pub mod tc_udp;
pub mod tm_udp;
pub mod protobuf;

pub mod utc_converter;
use std::sync::atomic::AtomicU32;

use async_trait::async_trait;
use msg::{Addr, YgwMessage};
use thiserror::Error;
use tokio::sync::mpsc::{Receiver, Sender};


static PARAMETER_ID_GENERATOR: AtomicU32 = AtomicU32::new(0);

pub type Result<T> = std::result::Result<T, YgwError>;

#[derive(Error, Debug)]
pub enum YgwError {
    #[error(transparent)]
    IOError(#[from] std::io::Error),

    #[error("decoding error: {0}")]
    DecodeError(String),

    #[error("node {0} has closed its channel")]
    TargetChannelClosed(u32),

    /// this is used to quit the run method of the nodes when the channel towards the server is closed
    #[error("server is shutting down")]
    ServerShutdown
}

/// A YGW node represents a connection to an end device.
/// The node appears as a link in Yamcs and can have sub-links.
///
/// The sub-link allow to separate data coming from a node into different streams in Yamcs.
/// Different TM pre-processor/ CMD post-processor can be set for each sub-link.
#[async_trait]
pub trait YgwNode: Send {
    /// the properties of the node - will be communicated to Yamcs
    fn properties(&self) -> &YgwLinkNodeProperties;
    /// the list of sub links - will also be communicated to Yamcs
    fn sub_links(&self) -> &[Link];

    /// method called by the ygw server to run the node
    /// tx and rx are used to communicate between the node and the server
    /// the node_id is the id allocated to this node, it has to be used for all the messages sent to the server
    async fn run(&mut self, node_id: u32, tx: Sender<YgwMessage>, mut rx: Receiver<YgwMessage>) -> Result<()>;
}

/// properties for a link or node
#[derive(Clone, Debug)]
pub struct YgwLinkNodeProperties {
    /// the name of the node has to be unique for a server    
    pub name: String,
    /// a description for the node. May be shown in the Yamcs web interface
    pub description: String,

    /// if this is set to true, Yamcs will setup a TM pre-processor for this link/node
    pub tm: bool,
    /// if this is set to true, Yamcs will setup a TC post-processor for this link/node
    pub tc: bool,
}

#[derive(Clone, Debug)]
pub struct Link {
    id: u32,
    props: YgwLinkNodeProperties,
}
impl Link {
    fn to_proto(&self) -> protobuf::ygw::Link {
        protobuf::ygw::Link {
            id: self.id,
            name: self.props.name.clone(),
            description: Some(self.props.description.clone()),
            tm: if self.props.tm { Some(true) } else { None },
            tc: if self.props.tc { Some(true) } else { None },
        }
    }
}

/// The LinkStatus can be used inside the nodes to keep track of the link status
/// It embeds a protobuf LinkStatus message which is cloned each time has to be sent out
/// The data_in and data_out functions can be called when new data is received or sent
/// One of the change_state, state_ok, state_failed functions can be called when the link goes up or down.
/// From time to time the send() can be called to send the data to Yamcs.
pub struct LinkStatus {
    addr: Addr,
    inner: protobuf::ygw::LinkStatus,
}

impl LinkStatus {
    pub fn new(addr: Addr) -> Self {
        LinkStatus {
            addr,
            inner: protobuf::ygw::LinkStatus {
                data_in_count: 0,
                data_out_count: 0,
                data_in_size: 0,
                data_out_size: 0,
                state: protobuf::ygw::LinkState::Ok as i32,
                err: None
            },
        }
    }

    /// increase the data in counters
    pub fn data_in(&mut self, count: u64, size: u64) {
        self.inner.data_in_count += count;
        self.inner.data_in_size += size;
    }

    /// increase the data out counter
    pub fn data_out(&mut self, count: u64, size: u64) {
        self.inner.data_out_count += count;
        self.inner.data_out_size += size;
    }

    /// change the state
    pub fn change_state(&mut self, state: i32, err: Option<String>) {
        self.inner.state = state;
        self.inner.err = err;
    }

    /// set the state to ok
    /// clear the error message
    pub fn state_ok(&mut self) {
        self.inner.state = protobuf::ygw::LinkState::Ok as i32;
        self.inner.err = None;
    }

    /// set the state to failed with the given message
    pub fn state_failed(&mut self, msg: &str) {
        self.inner.state = protobuf::ygw::LinkState::Failed as i32;
        self.inner.err = Some(msg.into());
    }

    /// send the status over the channel
    pub async fn send(
        &self,
        tx: &Sender<YgwMessage>,
    ) -> Result<()> {
         tx.send(YgwMessage::LinkStatus(self.addr, self.inner.clone()))
            .await.map_err(|_| YgwError::ServerShutdown)
    }
}

pub fn generate_pids(num_pids: u32) -> u32{
    PARAMETER_ID_GENERATOR.fetch_add(num_pids, std::sync::atomic::Ordering::Relaxed)
}

pub fn hex8(data: &[u8]) -> String {
    let hex_strings: Vec<String> = data.iter().map(|x| format!("{:02X}", x)).collect();
    hex_strings.join(" ")
}

#[cfg(test)]
mod tests {
   
}
